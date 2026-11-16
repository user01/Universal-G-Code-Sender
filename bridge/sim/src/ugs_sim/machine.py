"""Shapeoko 4 model on top of resurfacer's ``SimulatedGrbl``.

``SimulatedGrbl`` (``../resurfacer``, read-only) provides the GRBL core: 128-byte RX buffer,
15-block planner, ``ok`` on queue, syncing commands, homing/unlock/jog, hold/resume, soft
reset, probe alarms and probe surfaces. This subclass adds what a UGS session against a
Shapeoko 4 needs:

* settings and BitSetter position from the machine profile (``bridge/profiles``);
* **hardware reset** (USB DTR on connect): reported position restarts at 0,0,0 while the
  gantry physically stays where it was, so probing and BitSetter contact are computed from
  the *physical* position until the next ``$H``;
* the G-code words real job files and UGS send that the base rejects: ``T``, ``G17-G19``,
  ``G40``, ``G49``, ``G61``, ``G80``, ``G91.1``, ``M4`` (as ``M3``), ``M7/M8/M9``, arcs
  (``G2``/``G3`` approximated by their chord), and ``M6`` (``error:20`` like stock GRBL 1.1,
  or ignored);
* extended real-time bytes: safety door (0x84), feed/rapid/spindle overrides (0x90-0x9D),
  coolant toggles (0xA0/0xA1); other bytes >= 0x80 are dropped like GRBL does;
* critical alarms (1 hard limit, 2 soft limit): ``[MSG:Reset to continue]`` and every line is
  ignored until a soft reset;
* ``Ov:`` in status reports, ``T`` in ``$G``, ``$RST=#``, injected alarms and door events;
* probing in any direction (``G38.2``-``G38.5`` with X/Y/Z words) against a placed BitZero V2
  block with a bore (``ugs_sim.bitzero``), the tool modeled as a cylinder of
  ``tool_diameter_mm``;
* feed-hold deceleration: ``Hold:1`` lasts while the machine decelerates at the ``$120-$122``
  limit along the move, then ``Hold:0``.
"""

from __future__ import annotations

import math
import re
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass

from shapeoko_surface.controller.simulator import (
    PLACEHOLDER_SETTINGS,
    SimulatedGrbl,
    SimWorld,
    _Modal,
    _Segment,
)
from shapeoko_surface.core.geometry import Point2, Rect

from ugs_sim.bitzero import (
    DEFAULT_TOOL_DIAMETER,
    PLACEHOLDER_BORE_DIAMETER,
    PLACEHOLDER_BORE_OFFSET,
    SOURCED_SIZE_MM,
    BitZeroBlock,
    Interval,
    box_intervals,
    contact_lost,
    first_contact,
)
from ugs_sim.profile import MachineProfile

# Extended-ASCII real-time commands handled here (the base handles ? ! ~ 0x18 0x85).
DOOR = 0x84
FEED_OV_RESET, FEED_OV_PLUS_10, FEED_OV_MINUS_10, FEED_OV_PLUS_1, FEED_OV_MINUS_1 = range(
    0x90, 0x95
)
RAPID_OV_RESET, RAPID_OV_MEDIUM, RAPID_OV_LOW = 0x95, 0x96, 0x97
SPINDLE_OV_RESET, SPINDLE_OV_PLUS_10, SPINDLE_OV_MINUS_10 = 0x99, 0x9A, 0x9B
SPINDLE_OV_PLUS_1, SPINDLE_OV_MINUS_1, SPINDLE_OV_STOP = 0x9C, 0x9D, 0x9E
COOLANT_FLOOD_TOGGLE, COOLANT_MIST_TOGGLE = 0xA0, 0xA1
BASE_REALTIME = frozenset({ord("?"), ord("!"), ord("~"), 0x18, 0x85})

# Modal words accepted and ignored (mm, XY plane and absolute IJK are the only modes modeled).
IGNORED_G = frozenset({17.0, 18.0, 19.0, 21.0, 40.0, 49.0, 61.0, 80.0, 91.1})
ARC_WORDS = frozenset("IJKR")
CRITICAL_ALARMS = frozenset({1, 2})
PIN_SLACK = 1e-6  # mm: the pin reads closed this close to a surface (float noise at rest)
PROBE_AWAY = {38.4: 38.2, 38.5: 38.3}  # probe-away cycles and their toward twins


@dataclass(slots=True)
class _ProbeSegment(_Segment):
    away: bool = False


@dataclass
class WorldDefaults:
    """Physical layout placeholders (machine coordinates; Z values are tool-tip Z).

    Nothing here is measured. ``tool_length_mm`` is how far the tool tip sits below the
    spindle reference that GRBL's machine Z reports.
    """

    tool_length_mm: float = 40.0
    bitsetter_top_z: float = -105.0
    stock: tuple[float, float, float, float] = (-420.0, -420.0, -180.0, -325.0)
    stock_top_z: float = -95.6
    bitzero_plate_mm: float = 12.7
    tool_diameter_mm: float = DEFAULT_TOOL_DIAMETER


def settings_for(
    profile: MachineProfile,
    base: Mapping[int, float] | None = None,
    overrides: Mapping[int, float] | None = None,
) -> dict[int, float]:
    """Placeholder (or captured) settings, then profile travel and expected values."""
    settings = dict(base or PLACEHOLDER_SETTINGS)
    for n, t in zip((130, 131, 132), profile.travel, strict=True):
        settings[n] = t
    settings.update(profile.expected_settings)
    settings.update(overrides or {})
    return settings


def world_for(profile: MachineProfile, defaults: WorldDefaults | None = None) -> SimWorld:
    d = defaults or WorldDefaults()
    bx, by = profile.bitsetter.xy
    plate = profile.bitzero_plate_thickness
    return SimWorld(
        stock=Rect(*d.stock),
        stock_top_z=d.stock_top_z,
        bitzero_plate_mm=plate if plate is not None else d.bitzero_plate_mm,
        bitsetter_xy=Point2(bx, by),
        bitsetter_top_z=d.bitsetter_top_z,
        tool_length_mm=d.tool_length_mm,
    )


def _fmt(value: float) -> str:
    text = f"{value:.6f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


class ShapeokoGrbl(SimulatedGrbl):
    """``SimulatedGrbl`` configured from a machine profile, with the extensions above."""

    def __init__(
        self,
        profile: MachineProfile | None = None,
        *,
        settings: Mapping[int, float] | None = None,
        world: SimWorld | None = None,
        time_scale: float = 1.0,
        m6: str = "error",
        clock: Callable[[], float] | None = None,
    ) -> None:
        self.profile = profile or MachineProfile()
        # Set before the base constructor: it may call overridden methods.
        self.phys_shift = [0.0, 0.0, 0.0]
        """Physical machine position minus reported MPos; non-zero after a hardware reset."""
        self.homed = False
        self.critical = False
        self.door: int | None = None
        self.tool_number = 0
        self.feed_override = 100
        self.rapid_override = 100
        self.spindle_override = 100
        self.flood = False
        self.mist = False
        self.m6 = m6
        self._arc_modal = False
        self._last_ov: tuple[int, int, int] | None = None
        self.tool_diameter_mm = DEFAULT_TOOL_DIAMETER
        self.bitzero_block: BitZeroBlock | None = None
        """Placed BitZero V2; ``None`` = legacy plate that follows the tool over the stock."""
        p = self.profile
        self.bitzero_bore: tuple[float, tuple[float, float]] = (
            p.bitzero_bore_diameter or PLACEHOLDER_BORE_DIAMETER,
            p.bitzero_bore_center_to_corner or (PLACEHOLDER_BORE_OFFSET, PLACEHOLDER_BORE_OFFSET),
        )
        self.bitzero_bore_placeholder = (
            p.bitzero_bore_diameter is None or p.bitzero_bore_center_to_corner is None
        )
        self._probe_away = False
        self._decel_v = 0.0  # mm/s while a feed hold decelerates
        self._decel_a = 1.0  # mm/s^2 along the move
        super().__init__(
            settings if settings is not None else settings_for(self.profile),
            world or world_for(self.profile),
            clock=clock or time.monotonic,
            time_scale=time_scale,
        )

    # ------------------------------------------------------------- public API

    def tick(self) -> None:
        """Advance simulated time to now and process buffered input."""
        self._advance()

    def physical(self) -> tuple[float, float, float]:
        x, y, z = (m + s for m, s in zip(self.mpos, self.phys_shift, strict=True))
        return (x, y, z)

    def power_cycle(self) -> None:
        """Hardware reset (USB DTR / power): motion stops, position is no longer known."""
        physical = self.physical()
        self.planner.clear()
        self.rx.clear()
        self.out.clear()
        self.mpos = [0.0, 0.0, 0.0]
        self.phys_shift = list(physical)
        self.alarm = None
        self.hold = False
        self._hold_reported = False
        self.homing = False
        self.modal = _Modal()
        self.prb = (0.0, 0.0, 0.0, 0)
        self._last_wco_reported = None
        self._last_ov = None
        self.status_count = 0
        self._reset_volatile()
        self.homed = False
        self._power_on()

    def inject_alarm(self, code: int) -> None:
        self._raise_alarm(code)

    def open_door(self) -> None:
        """Safety door opened: feed hold, ``Door:1`` until closed."""
        self.door = 1
        self._begin_hold()

    def close_door(self) -> None:
        if self.door is not None:
            self.door = 0

    def set_tool_diameter(self, mm: float) -> None:
        if not (0 < mm < 100):
            raise ValueError("tool diameter must be between 0 and 100 mm")
        self.tool_diameter_mm = mm

    def place_bitzero(self, corner: tuple[float, float] | None = None) -> BitZeroBlock:
        """Hook the block's lips over ``corner`` (default: the stock's front-left corner)."""
        stock = self.world.stock
        dia, offset = self.bitzero_bore
        self.bitzero_block = BitZeroBlock(
            corner=corner or (stock.x_min, stock.y_min),
            stock_top_z=self.world.stock_top_z,
            thickness=self.world.bitzero_plate_mm,
            size=self.profile.bitzero_outer_size or SOURCED_SIZE_MM,
            bore_diameter=dia,
            bore_offset=offset,
        )
        self.surface("bitzero").active = True
        return self.bitzero_block

    def set_bitzero_bore(self, diameter: float, offset: tuple[float, float]) -> None:
        if diameter <= 0 or min(offset) <= diameter / 2:
            raise ValueError("bore must have a positive diameter and fit inside the corner")
        self.bitzero_bore = (diameter, offset)
        self.bitzero_bore_placeholder = False
        if self.bitzero_block is not None:
            self.bitzero_block.bore_diameter = diameter
            self.bitzero_block.bore_offset = offset

    def follow_bitzero(self) -> None:
        """Back to the legacy plate that is assumed under the tool wherever it probes the stock."""
        self.bitzero_block = None

    def bitzero_info(self) -> dict[str, object]:
        info: dict[str, object] = {
            "mode": "follow" if self.bitzero_block is None else "placed",
            "present": self.surface("bitzero").active,
            "thickness": self.world.bitzero_plate_mm,
            "bore_diameter": self.bitzero_bore[0],
            "bore_offset": list(self.bitzero_bore[1]),
            "bore_placeholder": self.bitzero_bore_placeholder,
        }
        if self.bitzero_block is not None:
            info.update(self.bitzero_block.describe())
        return info

    def snapshot(self) -> dict[str, object]:
        return {
            "state": self.state,
            "mpos": list(self.mpos),
            "physical": list(self.physical()),
            "homed": self.homed,
            "wco_g54": list(self.wco[54]),
            "tool_length_mm": self.world.tool_length_mm,
            "tool_number": self.tool_number,
            "tool_diameter_mm": self.tool_diameter_mm,
            "bitzero": self.bitzero_info(),
            "probe": self.probe_triggered(),
            "planner": len(self.planner),
            "rx_bytes": len(self.rx),
            "rx_overflow": self.rx_overflow,
            "lines_received": len(self.received_lines),
            "overrides": [self.feed_override, self.rapid_override, self.spindle_override],
            "time_scale": self.time_scale,
        }

    # -------------------------------------------------------------- transport

    def write(self, data: bytes) -> None:
        start = 0
        for i, byte in enumerate(data):
            if byte < 0x80:
                continue
            if i > start:
                super().write(data[start:i])
            start = i + 1
            if byte == 0x85:
                super().write(bytes([byte]))
            else:
                self._extended_realtime(byte)
        if start < len(data):
            super().write(data[start:])

    def _extended_realtime(self, byte: int) -> None:
        if byte == DOOR:
            # Host-sent door: no switch is actually open, so it is ready to resume (Door:0).
            self.door = 0
            self._begin_hold()
        elif byte == FEED_OV_RESET:
            self.feed_override = 100
        elif byte in (FEED_OV_PLUS_10, FEED_OV_MINUS_10, FEED_OV_PLUS_1, FEED_OV_MINUS_1):
            delta = {FEED_OV_PLUS_10: 10, FEED_OV_MINUS_10: -10, FEED_OV_PLUS_1: 1}.get(byte, -1)
            self.feed_override = max(10, min(200, self.feed_override + delta))
        elif byte in (RAPID_OV_RESET, RAPID_OV_MEDIUM, RAPID_OV_LOW):
            self.rapid_override = {RAPID_OV_RESET: 100, RAPID_OV_MEDIUM: 50}.get(byte, 25)
        elif byte == SPINDLE_OV_RESET:
            self.spindle_override = 100
        elif byte in (
            SPINDLE_OV_PLUS_10,
            SPINDLE_OV_MINUS_10,
            SPINDLE_OV_PLUS_1,
            SPINDLE_OV_MINUS_1,
        ):
            delta = {SPINDLE_OV_PLUS_10: 10, SPINDLE_OV_MINUS_10: -10, SPINDLE_OV_PLUS_1: 1}.get(
                byte, -1
            )
            self.spindle_override = max(10, min(200, self.spindle_override + delta))
        elif byte == COOLANT_FLOOD_TOGGLE:
            self.flood = not self.flood
        elif byte == COOLANT_MIST_TOGGLE:
            self.mist = not self.mist
        # Anything else >= 0x80 is discarded, as GRBL does.

    def _realtime(self, byte: int) -> None:
        if byte == ord("~") and self.door is not None:
            if self.door == 0:
                self.door = None
                self.hold = False
            return
        if byte == ord("~") and self.hold and not self._hold_reported:
            return  # GRBL resumes only once the hold is complete (Hold:0)
        if byte == ord("!"):
            if self.planner and not self.homing and self.alarm is None:
                if self.planner[0].kind == "jog":
                    self._cancel_jog()
                else:
                    self._begin_hold()
            return
        super()._realtime(byte)

    # ----------------------------------------------------------- feed hold

    def _begin_hold(self) -> None:
        """Start a feed hold: decelerate along the path, then report ``Hold:0``."""
        if self.hold:
            return  # already holding (feed hold or door): keep its progress
        self.hold = True
        self._hold_reported = False
        self._decel_v = 0.0
        seg = self.planner[0] if self.planner else None
        if seg is None or seg.kind not in ("line", "jog", "probe"):
            self._hold_reported = True  # nothing moving: the hold is complete at once
            return
        direction = [t - p for t, p in zip(seg.target, self.mpos, strict=True)]
        length = math.hypot(*direction)
        if length <= 1e-9:
            self._hold_reported = True
            return
        accel = [self.settings.get(n, 200.0) for n in (120, 121, 122)]
        # GRBL limits the acceleration along a move so that no axis exceeds its own $12x.
        self._decel_a = min(
            a / abs(d / length) for a, d in zip(accel, direction, strict=True) if abs(d) > 1e-12
        )
        self._decel_v = self._speed(seg)

    def _speed(self, seg: _Segment) -> float:
        """Programmed speed of ``seg`` in mm/s, with the feed override for feed moves."""
        factor = self.feed_override / 100.0 if seg.kind == "line" else 1.0
        return seg.rate_mm_min / 60.0 * factor

    def _decelerate(self, dt: float) -> float:
        """Advance a decelerating hold by ``dt`` seconds; return the time left after stopping."""
        v, a = self._decel_v, self._decel_a
        stop_t = v / a
        used = min(dt, stop_t)
        distance = v * used - 0.5 * a * used * used
        self._decel_v = max(0.0, v - a * used)
        while distance > 1e-12 and self.planner and self.planner[0].kind in ("line", "jog"):
            seg = self.planner[0]
            speed = self._speed(seg)
            distance = self._step(seg, distance / speed) * speed
        if distance > 1e-12 and self.planner and self.planner[0].kind == "probe":
            seg = self.planner[0]
            self._step(seg, distance / self._speed(seg))
        if used >= stop_t or not self.planner or self.planner[0].kind not in ("line", "jog"):
            self._decel_v = 0.0
            self._hold_reported = True
        return dt - used

    def _advance(self) -> None:
        now = self._clock()
        dt = (now - self._last) * self.time_scale
        self._last = now
        if self.hold and not self._hold_reported and self._decel_v > 0:
            dt = self._decelerate(dt)
        elif self.hold and not self._hold_reported:
            self._hold_reported = True
        while dt > 1e-12 and self.planner and not self.hold:
            dt = self._step(self.planner[0], dt)
        self._process_rx()

    # ------------------------------------------------------------ state/status

    @property
    def state(self) -> str:
        if self.homing:
            return "Home"  # the base reports Alarm until the lock clears at the end
        base = super().state
        if self.door is not None and base != "Alarm":
            return f"Door:{self.door}"
        return base

    def _status_line(self) -> str:
        # The base marks a hold complete once it is reported; here deceleration decides.
        complete = self._hold_reported
        line = super()._status_line()
        self._hold_reported = complete
        if self.hold and self._decel_v > 0 and not complete:
            line = re.sub(r"\|FS:\d+,", f"|FS:{self._decel_v * 60:.0f},", line, count=1)
        ov = (self.feed_override, self.rapid_override, self.spindle_override)
        extra: list[str] = []
        if ov != self._last_ov or self.status_count % 10 == 2:
            extra.append("Ov:" + ",".join(str(v) for v in ov))
            self._last_ov = ov
        acc = ("S" if self.modal.spindle_on else "") + ("F" if self.flood else "")
        acc += "M" if self.mist else ""
        body = line[1:-1].split("|")
        body = [p for p in body if not p.startswith("A:")]
        if acc:
            extra.append("A:" + acc)
        return "<" + "|".join(body + extra) + ">"

    # --------------------------------------------------------------- physics

    def tip_z(self) -> float:
        return self.physical()[2] - self.world.tool_length_mm

    def probe_triggered(self) -> bool:
        if self.manual_probe_contact:
            return True
        here = self.physical()
        return first_contact(self._contact_intervals(here, here, PIN_SLACK)) is not None

    def _contact_intervals(
        self, a: tuple[float, float, float], b: tuple[float, float, float], slack: float = 0.0
    ) -> list[Interval]:
        """Where along ``a -> b`` (physical spindle coordinates) the probe input is closed."""
        length = self.world.tool_length_mm
        out: list[Interval] = []
        for s in self.surfaces:
            if not s.active:
                continue
            if s.follows_tool:
                if self.bitzero_block is not None:
                    out += self.bitzero_block.contact_intervals(
                        a, b, length, self.tool_diameter_mm / 2.0, slack
                    )
                    continue
                if self._follow_xy is None:
                    continue
            r = s.rect
            rect = (r.x_min, r.y_min, r.x_max, r.y_max)
            out += box_intervals(a, b, rect, s.top_z + length, slack)
        return out

    def _probe(self, words: list[tuple[str, float]], alarm_on_fail: bool) -> None:
        away, self._probe_away = self._probe_away, False
        feed = next((v for w, v in words if w == "F"), self.modal.feed)
        if feed <= 0:
            self.out.append("error:22")
            return
        target = self._target(words, self.modal.absolute, machine=False)
        if math.dist(target, self.mpos) <= 1e-9:
            self.out.append("error:20")  # GRBL: invalid target for a probe cycle
            return
        px, py, _ = self.physical()
        legacy = self.bitzero_block is None and self.surface("bitzero").rect.contains(
            Point2(px, py)
        )
        self._follow_xy = (px, py) if legacy else None
        if self.probe_triggered() != away:
            # Toward: already touching. Away: not touching. Both are ALARM:4 in GRBL.
            self._follow_xy = None
            self._raise_alarm(4)
            return
        self.planner.append(
            _ProbeSegment(target, feed, "probe", probe_alarm_on_fail=alarm_on_fail, away=away)
        )

    def _probe_step(self, seg: _Segment, dt: float) -> float:
        start = (self.mpos[0], self.mpos[1], self.mpos[2])
        dist = math.dist(start, seg.target)
        speed = seg.rate_mm_min / 60.0
        if dist <= 1e-9:
            self._finish(seg)
            return dt
        travel = min(dist, speed * dt)
        sx, sy, sz = self.phys_shift
        tx, ty, tz = seg.target
        intervals = self._contact_intervals(
            (start[0] + sx, start[1] + sy, start[2] + sz), (tx + sx, ty + sy, tz + sz)
        )
        if self.manual_probe_contact:
            intervals.append((-math.inf, math.inf))
        away = isinstance(seg, _ProbeSegment) and seg.away
        t = contact_lost(intervals) if away else first_contact(intervals)
        if t is not None and t * dist <= travel + 1e-9:
            hit = [s + (e - s) * t for s, e in zip(start, seg.target, strict=True)]
            self.prb = (hit[0], hit[1], hit[2], 1)
            self.out.append(f"[PRB:{hit[0]:.3f},{hit[1]:.3f},{hit[2]:.3f}:1]")
            if away:  # stop just past the point where the circuit opened
                t = min(1.0, t + 1e-3 / dist)
                hit = [s + (e - s) * t for s, e in zip(start, seg.target, strict=True)]
            self.mpos = hit
            self.planner.popleft()
            self._follow_xy = None
            self.out.append("ok")
            return dt - t * dist / speed
        frac = travel / dist
        self.mpos = [s + (e - s) * frac for s, e in zip(start, seg.target, strict=True)]
        if travel >= dist - 1e-9:
            self.mpos = list(seg.target)
            self._finish(seg)
            return dt - dist / speed
        return 0.0

    def _step(self, seg: _Segment, dt: float) -> float:
        if seg.kind == "probe":
            return self._probe_step(seg, dt)
        factor = self.feed_override / 100.0 if seg.kind == "line" else 1.0
        if factor == 1.0 or math.isinf(dt):
            return super()._step(seg, dt)
        return super()._step(seg, dt * factor) / factor

    def _finish(self, seg: _Segment) -> None:
        if seg.kind == "home":
            self.phys_shift = [0.0, 0.0, 0.0]
            self.homed = True
        super()._finish(seg)

    # ----------------------------------------------------------------- resets

    def _reset_volatile(self) -> None:
        self.critical = False
        self.door = None
        self.tool_number = 0
        self.feed_override = self.rapid_override = self.spindle_override = 100
        self.flood = self.mist = False
        self._arc_modal = False

    def _soft_reset(self) -> None:
        stopped = self.hold and self._hold_reported
        moving = self.homing or (any(s.kind != "dwell" for s in self.planner) and not stopped)
        if moving:
            self.homed = False
        self._reset_volatile()
        super()._soft_reset()

    def _raise_alarm(self, code: int) -> None:
        super()._raise_alarm(code)
        self.door = None
        if code in CRITICAL_ALARMS:
            self.critical = True
            self.homed = False
            self.out.append("[MSG:Reset to continue]")

    # ------------------------------------------------------------ line input

    def _execute(self, line: str) -> None:
        if self.critical:
            return  # GRBL blocks everything but real-time commands until reset
        super()._execute(line)

    @staticmethod
    def _needs_sync(line: str) -> bool:
        if line == "$G":
            return False
        return SimulatedGrbl._needs_sync(line)

    def _dollar(self, line: str) -> None:
        if line == "$G":
            m = self.modal
            coolant = " ".join(c for c, on in (("M7", self.mist), ("M8", self.flood)) if on)
            motion = "G2" if self._arc_modal and m.motion == 1 else f"G{m.motion}"
            self.out.append(
                f"[GC:{motion} G{m.coord_system} G17 G21 {'G90' if m.absolute else 'G91'} "
                f"G94 M{'3' if m.spindle_on else '5'} {coolant or 'M9'} T{self.tool_number} "
                f"F{m.feed:.0f} S{m.spindle_speed:.0f}]"
            )
            self.out.append("ok")
            return
        if line == "$RST=#" and self.state in ("Idle", "Alarm"):
            for n in self.wco:
                self.wco[n] = [0.0, 0.0, 0.0]
            self.out.append("ok")
            return
        super()._dollar(line)

    def _gcode(self, line: str) -> None:
        words = self._words(line)
        if words is None:
            self.out.append("error:2")
            return
        gs = {v for w, v in words if w == "G"}
        away = gs & PROBE_AWAY.keys()
        if away:
            # The base knows G38.2/G38.3 only: run those with the direction flag set.
            self._probe_away = True
            words = [
                ("G", PROBE_AWAY[v]) if w == "G" and v in PROBE_AWAY else (w, v) for w, v in words
            ]
            gs = {v for w, v in words if w == "G"}
        arc = bool(gs & {2.0, 3.0})
        if gs & {0.0, 1.0, 38.2, 38.3, 38.4, 38.5}:
            self._arc_modal = False
        arc_line = arc or (self._arc_modal and any(w in ARC_WORDS for w, _ in words))
        keep: list[tuple[str, float]] = []
        for w, v in words:
            if w == "T":
                self.tool_number = int(v)
            elif w == "G" and v in IGNORED_G:
                continue
            elif w == "G" and v in (2.0, 3.0):
                keep.append(("G", 1.0))  # arc approximated by its chord
            elif arc_line and (w in ARC_WORDS or (w == "P" and arc)):
                continue
            elif w == "M" and v == 6:
                if self.m6 == "error":
                    self.out.append("error:20")
                    return
            elif w == "M" and v in (7.0, 8.0, 9.0):
                self.mist = v == 7 or (self.mist and v != 9)
                self.flood = v == 8 or (self.flood and v != 9)
            elif w == "M" and v == 4:
                keep.append(("M", 3.0))
            else:
                keep.append((w, v))
        if arc:
            self._arc_modal = True
        if not keep:
            self.out.append("ok")
            return
        try:
            super()._gcode("".join(f"{w}{_fmt(v)}" for w, v in keep))
        finally:
            self._probe_away = False

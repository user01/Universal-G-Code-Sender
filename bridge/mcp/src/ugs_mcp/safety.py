"""Safety checks for everything Claude sends (bridge/SPEC.md §5.2).

Two layers:

* **Authority classification** of command lines (bridge/docs/api.md, "Authority
  classification"). The Java bridge enforces it; the MCP server applies it again.
* **The motion checker** (:func:`check_motion`): the lines of one tool call are parsed with
  the G-code interpreter (:mod:`ugs_mcp.gcode`), modal state is tracked across them, and
  every target is checked against the machine profile's envelope. It refuses, with a
  message that says what to do instead: motion before homing, targets outside the profile
  envelope, unbounded or absolute probes, probing with the input already active, leaving
  G91/G20/G93 (or another non-default mode) at the end of the call, ``M6``, spindle start,
  program-flow codes, setting writes, and anything GRBL 1.1 doesn't support.

Spindle (D21): Claude may switch the router off (``M5``, ``S0``) but never on. ``M3``/``M4``
are refused, and so is a non-zero ``S`` word: it only matters with the router on, where it
changes the spindle output (on the BitRunner a non-zero S after ``M3 S0`` switches the router
on). The real-time ``hold``/``reset`` paths are separate and unaffected.

Positions are tracked as an interval per axis in machine coordinates: after a probe the
contact point is somewhere between the start and the probe target, so a following relative
move is checked at both ends.
"""

from __future__ import annotations

import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, replace
from enum import StrEnum
from typing import Any

from ugs_mcp.gcode.interpreter import (
    MM_PER_INCH,
    PLANES,
    interpret,
)
from ugs_mcp.gcode.lexer import code_name, lex, lex_line, split_comments


class Level(StrEnum):
    OBSERVE = "observe"
    QUERY = "query"
    MOTION = "motion"
    STREAM = "stream"


_ORDER = {Level.OBSERVE: 0, Level.QUERY: 1, Level.MOTION: 2, Level.STREAM: 3}


def level_allows(granted: Level | str, required: Level | str) -> bool:
    return _ORDER[Level(granted)] >= _ORDER[Level(required)]


class LineClass(StrEnum):
    REALTIME = "realtime"
    QUERY = "query"
    CONFIRM = "confirm"
    MOTION = "motion"
    STREAM = "stream"


QUERY_LINES = frozenset({"?", "$$", "$#", "$G", "$I", "$N"})
CONFIRM_LINES = frozenset({"$H", "$X"})
REALTIME_OPS = frozenset({"hold", "resume", "jog_cancel", "reset", "status"})
_SETTING_WRITE = re.compile(r"^\$(\d+=|N\d+=|RST=)")


@dataclass(frozen=True, slots=True)
class Classification:
    line: str
    """Normalized: comments and whitespace removed, uppercase."""
    cls: LineClass
    level: Level
    """Minimum authority level."""
    operator_dialog: bool = False
    refused_by_default: bool = False
    reason: str = ""


def normalize(line: str) -> str:
    code, _, _ = split_comments(line)
    return "".join(code.split()).upper()


def classify_line(line: str) -> Classification:
    """Classify one line for ``POST /command``."""
    norm = normalize(line)
    if norm in QUERY_LINES:
        return Classification(norm, LineClass.QUERY, Level.QUERY, reason="non-motion query")
    if not norm:
        return Classification(norm, LineClass.QUERY, Level.QUERY, reason="empty or comment only")
    if norm in CONFIRM_LINES:
        return Classification(
            norm,
            LineClass.CONFIRM,
            Level.MOTION,
            operator_dialog=True,
            reason=f"{norm} always needs the operator's confirmation in UGS",
        )
    if _SETTING_WRITE.match(norm):
        return Classification(
            norm,
            LineClass.MOTION,
            Level.MOTION,
            refused_by_default=True,
            reason="writes a controller setting or startup block",
        )
    if norm.startswith("$J="):
        return Classification(norm, LineClass.MOTION, Level.MOTION, reason="jog")
    return Classification(norm, LineClass.MOTION, Level.MOTION, reason="G/M code or other command")


def classify_realtime(op: str) -> Classification:
    if op not in REALTIME_OPS:
        raise ValueError(f"unknown real-time op {op!r}")
    return Classification(op, LineClass.REALTIME, Level.OBSERVE, reason="always allowed")


def stream_classification() -> Classification:
    """``POST /file/run`` and ``POST /job/resume``."""
    return Classification(
        "", LineClass.STREAM, Level.STREAM, operator_dialog=True, reason="runs a file"
    )


def check_lines(lines: list[str], granted: Level | str) -> list[str]:
    """Problems that stop the request before it reaches the bridge (empty = OK).

    Mirrors the bridge: one line above the granted level rejects the whole request.
    """
    problems: list[str] = []
    for i, ln in enumerate(lines):
        c = classify_line(ln)
        if c.refused_by_default:
            problems.append(f"line {i + 1} '{c.line}': refused, {c.reason}")
        elif not level_allows(granted, c.level):
            problems.append(f"line {i + 1} '{c.line}': needs {c.level.value} authority")
    return problems


# ------------------------------------------------------------------------------------------
# Motion checker (SPEC §5.2)
# ------------------------------------------------------------------------------------------

AXES = "XYZ"
PROBE_MAX_MM = 25.0
"""SPEC §5.1: a probe from Claude travels at most 25 mm."""
MAX_LINES = 50
EPS = 1e-6

POLICY_REFUSED_G: dict[str, str] = {
    "G28": "G28 moves through a stored position the bridge can't check; use move instead",
    "G30": "G30 moves through a stored position the bridge can't check; use move instead",
    "G28.1": "G28.1 overwrites a stored position; not available to Claude",
    "G30.1": "G30.1 overwrites a stored position; not available to Claude",
    "G92": "G92 shifts the coordinate system invisibly; use set_work_offset (G10 L2 P1)",
    "G92.1": "G92.1 changes the coordinate system; not available to Claude",
    "G43.1": "dynamic tool length offsets would fight the BitSetter workflow (SPEC §11.4)",
    "G38.4": "probe-away is not used here; probe toward the surface with G38.2",
    "G38.5": "probe-away is not used here; probe toward the surface with G38.2",
    **{
        f"G{n}": "the bridge tracks only G54; other work coordinate systems are refused"
        for n in (55, 56, 57, 58, 59)
    },
}
SPINDLE_START = frozenset({"M3", "M4"})
SPINDLE_REFUSAL = (
    "Claude never switches the router on; the operator starts the router (or a file run they"
    " confirm). Claude may switch it off with M5."
)
PROGRAM_FLOW = frozenset({"M0", "M1", "M2", "M30"})
DEFAULT_MODAL = {"units": "G21", "distance": "G90", "feed_mode": "G94", "plane": "G17"}
MODE_WORDS = {
    "G20": "G20 (inches)",
    "G91": "G91 (incremental distances)",
    "G93": "G93 (inverse-time feed)",
    "G18": "G18 (XZ plane)",
    "G19": "G19 (YZ plane)",
}
FIX_WORD = {"units": "G21", "distance": "G90", "feed_mode": "G94", "plane": "G17"}
MOTION_MODES = frozenset({"G0", "G1", "G2", "G3", "G38.2", "G38.3", "G38.4", "G38.5", "G80"})


@dataclass(slots=True)
class Modal:
    """The GRBL modal state that changes how later lines are read."""

    units: str = "G21"
    distance: str = "G90"
    feed_mode: str = "G94"
    plane: str = "G17"
    wcs: str = "G54"
    motion: str = "G0"
    feed: float | None = None
    """mm/min (G94) or the inverse-time value (G93)."""
    known: bool = True
    """False when the starting state was assumed rather than read from ``$G``."""

    def leftovers(self) -> list[tuple[str, str]]:
        """Non-default modes still active: (field, active word)."""
        return [
            (key, getattr(self, key))
            for key, default in DEFAULT_MODAL.items()
            if getattr(self, key) != default
        ]

    @property
    def safe(self) -> bool:
        return not self.leftovers()

    def words(self) -> str:
        return f"{self.units} {self.distance} {self.feed_mode} {self.plane} {self.wcs}"

    @classmethod
    def parse_gc(cls, text: str) -> Modal | None:
        """Parse a ``$G`` reply (``[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0 F0 S0]``)."""
        m = re.search(r"\[GC:([^\]]*)\]", text)
        if not m:
            return None
        md = cls()
        feed: float | None = None
        for tok in m.group(1).split():
            letter, value = tok[0], tok[1:]
            try:
                num = float(value)
            except ValueError:
                continue
            word = code_name(letter, num) if letter in "GM" else tok
            if word in ("G20", "G21"):
                md.units = word
            elif word in ("G90", "G91"):
                md.distance = word
            elif word in ("G93", "G94"):
                md.feed_mode = word
            elif word in PLANES:
                md.plane = word
            elif word in ("G54", "G55", "G56", "G57", "G58", "G59"):
                md.wcs = word
            elif word in MOTION_MODES:
                md.motion = word
            elif letter == "F":
                feed = num
        if feed is not None and feed > 0:
            md.feed = feed * (MM_PER_INCH if md.units == "G20" else 1.0)
        return md


@dataclass(frozen=True, slots=True)
class Envelope:
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    verified: bool = False

    def describe(self, axis: int) -> str:
        return f"{AXES[axis]} {_n(self.lo[axis])}..{_n(self.hi[axis])}"


@dataclass(slots=True)
class Limits:
    """What the checker takes from the machine profile (bridge/profiles, SPEC §7)."""

    envelope: Envelope | None
    safe_z: float | None = None
    feed_max: float = 5000.0
    probe_max: float = PROBE_MAX_MM
    probe_feed_max: float = 800.0
    xy_probe: bool = False
    xy_probe_reason: str = ""
    plate_thickness: float | None = None
    plate_verified: bool = False
    plate_source: str | None = None
    bitsetter_xy: tuple[float, float] | None = None
    source: str = "profile"
    assumptions: list[str] = field(default_factory=list)
    """Unverified profile values the checks rely on (machine ledger ids)."""

    @classmethod
    def from_profile(cls, profile: Mapping[str, Any] | None, source: str | None = None) -> Limits:
        p = dict(profile or {})
        env = p.get("envelope_mm") or {}
        envelope: Envelope | None = None
        assumptions: list[str] = []
        try:
            lo = [float(v) for v in env["min"]]
            hi = [float(v) for v in env["max"]]
            if len(lo) == 3 and len(hi) == 3 and all(a < b for a, b in zip(lo, hi, strict=True)):
                envelope = Envelope(
                    (lo[0], lo[1], lo[2]), (hi[0], hi[1], hi[2]), bool(env.get("verified"))
                )
        except (KeyError, TypeError, ValueError):
            envelope = None
        if envelope is not None and not envelope.verified:
            assumptions.append(
                "envelope "
                + ", ".join(envelope.describe(i) for i in range(3))
                + " mm comes from the profile and is not measured (ledger A1/C6; the"
                " controller's $130-$132 are not used)"
            )
        safe_z = p.get("safe_z_machine")
        bz = p.get("bitzero") or {}
        bs = p.get("bitsetter") or {}
        xy_probe = bz.get("bore_diameter") is not None
        reason = (
            ""
            if xy_probe
            else "X/Y probing needs the BitZero bore geometry (profile bitzero.bore_diameter,"
            " machine ledger B3: unknown), so only Z probing is allowed. Probe Z, or measure"
            " the bore and add it to the profile first."
        )
        if xy_probe and bz.get("bore_center_to_corner") is None:
            assumptions.append("bitzero.bore_center_to_corner is unknown (ledger B3)")
        feeds = [
            float(v)
            for v in (bz.get("fast_feed"), bs.get("fast_feed"))
            if isinstance(v, int | float) and v > 0
        ]
        plate = bz.get("plate_thickness")
        rapid = p.get("rapid_mm_min")
        safe = float(safe_z) if isinstance(safe_z, int | float) else None
        if safe is not None:
            assumptions.append(f"safe height machine Z {_n(safe)} is assumed (profile)")
        return cls(
            envelope=envelope,
            safe_z=safe,
            feed_max=float(rapid) if isinstance(rapid, int | float) and rapid > 0 else 5000.0,
            probe_max=min(PROBE_MAX_MM, float(bz.get("max_travel") or PROBE_MAX_MM)),
            probe_feed_max=max(feeds) if feeds else 800.0,
            xy_probe=xy_probe,
            xy_probe_reason=reason,
            plate_thickness=float(plate) if isinstance(plate, int | float) else None,
            plate_verified=bool(bz.get("plate_thickness_verified")),
            plate_source=str(bz.get("plate_thickness_source") or "") or None,
            bitsetter_xy=_xy(bs.get("xy")),
            source=source or "profile",
            assumptions=assumptions,
        )


Vec3 = tuple[float, float, float]


def _xy(v: Any) -> tuple[float, float] | None:
    try:
        return (float(v[0]), float(v[1]))
    except (TypeError, ValueError, IndexError):
        return None


def _vec(v: Any) -> Vec3 | None:
    if not v or len(v) != 3:
        return None
    return (float(v[0]), float(v[1]), float(v[2]))


STALE_REPORT_S = 5.0
"""No controller status report for this long while connected: the controller is silent."""


@dataclass(slots=True)
class Machine:
    """The bridge's view of the machine when the call starts (from ``GET /status``)."""

    connected: bool = True
    locked: bool = False
    level: str = "motion"
    state: str | None = "Idle"
    homed: bool = True
    mpos: Vec3 | None = (0.0, 0.0, 0.0)
    wco: Vec3 | None = (0.0, 0.0, 0.0)
    probe_pin: bool | None = False
    alarm: str | None = None
    stream: str = "idle"
    interceptor: str | None = None
    report_age_s: float | None = None
    """Seconds since the last controller status report (None: the bridge doesn't say)."""

    @classmethod
    def from_status(cls, st: Mapping[str, Any]) -> Machine:
        conn = st.get("connection") or {}
        ctrl = st.get("controller") or {}
        auth = st.get("authority") or {}
        alarm = st.get("alarm")
        pins = ctrl.get("pins")
        icp = st.get("interceptor")
        rep = st.get("controller_report")
        age = rep.get("age_s") if isinstance(rep, dict) else None
        return cls(
            connected=bool(conn.get("connected")),
            locked=bool(auth.get("locked")),
            level=str(auth.get("level") or "observe"),
            state=ctrl.get("state") if ctrl else None,
            homed=bool(st.get("homed_this_connection")),
            mpos=_vec(ctrl.get("mpos")),
            wco=_vec(ctrl.get("wco")),
            probe_pin=None if not isinstance(pins, dict) else bool(pins.get("probe")),
            alarm=(
                f"ALARM:{alarm.get('code')} {alarm.get('text') or ''}".strip()
                if isinstance(alarm, dict)
                else None
            ),
            stream=str((st.get("stream") or {}).get("state") or "idle"),
            interceptor=str(icp.get("state") or "active") if isinstance(icp, dict) else None,
            report_age_s=None if age is None else float(age),
        )


@dataclass(frozen=True, slots=True)
class Refusal:
    rule: str
    message: str
    line: int | None = None
    """1-based index into the call's lines."""

    def text(self, lines: Sequence[str]) -> str:
        if self.line is None:
            return self.message
        src = lines[self.line - 1].strip() if 0 < self.line <= len(lines) else "?"
        return f"line {self.line} '{src}': {self.message}"


@dataclass(slots=True)
class Planned:
    line: int
    kind: str
    """``rapid``, ``feed``, ``arc``, ``probe``, ``jog``."""
    lo: Vec3
    hi: Vec3
    length: float
    rate: float | None


Span = list[list[float]]
"""Per axis [lo, hi] in machine coordinates."""


@dataclass(slots=True)
class Check:
    lines: list[str]
    refusals: list[Refusal] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    moves: list[Planned] = field(default_factory=list)
    modal_after: list[Modal] = field(default_factory=list)
    """Modal state after each line (index = line - 1)."""
    start_modal: Modal = field(default_factory=Modal)
    end_modal: Modal = field(default_factory=Modal)
    end_span: Span | None = None
    end_wco: Vec3 | None = None
    moves_machine: bool = False
    sets_offset: bool = False
    probes: int = 0
    confirm: str | None = None
    """``$H`` or ``$X`` when the call is one of those (operator-confirmed)."""

    @property
    def ok(self) -> bool:
        return not self.refusals

    @property
    def rules(self) -> list[str]:
        return [r.rule for r in self.refusals]

    def refuse(self, rule: str, message: str, line: int | None = None) -> None:
        r = Refusal(rule, message, line)
        if r not in self.refusals:
            self.refusals.append(r)

    def message(self) -> str:
        head = "Refused, nothing was sent:"
        return "\n".join([head, *(f"- {r.text(self.lines)}" for r in self.refusals)])

    def estimate_s(self, rapid: float) -> float:
        total = 0.0
        for mv in self.moves:
            rate = mv.rate or rapid
            total += mv.length / max(rate, 1.0) * 60.0
        return total

    def modal_before(self, line: int) -> Modal:
        """Modal state just before 1-based ``line``."""
        return self.modal_after[line - 2] if line >= 2 else self.start_modal


def _n(v: float) -> str:
    text = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def fmt_num(v: float) -> str:
    """A G-code number: at most three decimals, no trailing zeros."""
    return _n(v)


def _exact(span: Span, axis: int) -> float | None:
    lo, hi = span[axis]
    return lo if abs(hi - lo) < EPS else None


def _ends(span: Span) -> tuple[Vec3, Vec3]:
    return (span[0][0], span[1][0], span[2][0]), (span[0][1], span[1][1], span[2][1])


def _hull(a: Span, b: Span) -> tuple[Vec3, Vec3]:
    lo = [min(a[i][0], b[i][0]) for i in range(3)]
    hi = [max(a[i][1], b[i][1]) for i in range(3)]
    return (lo[0], lo[1], lo[2]), (hi[0], hi[1], hi[2])


class _Ctx:
    """Mutable state while walking one call's lines."""

    def __init__(
        self, check: Check, machine: Machine, limits: Limits, modal: Modal, advisory: bool
    ) -> None:
        self.c = check
        self.m = machine
        self.lim = limits
        self.modal = modal
        self.advisory = advisory
        self.span: Span | None = [[v, v] for v in machine.mpos] if machine.mpos else None
        self.wco: list[float] | None = list(machine.wco) if machine.wco else None

    def in_envelope(
        self,
        n: int,
        lo: Sequence[float],
        hi: Sequence[float],
        what: str,
        axes: Sequence[int] = (0, 1, 2),
    ) -> None:
        """Refuse if [lo, hi] leaves the envelope on any of ``axes`` (the axes this line
        moves; the others were checked when they last moved)."""
        env = self.lim.envelope
        if env is None:
            return
        for i in axes:
            bad = None
            if lo[i] < env.lo[i] - EPS:
                bad = lo[i]
            elif hi[i] > env.hi[i] + EPS:
                bad = hi[i]
            if bad is not None:
                self.c.refuse(
                    "envelope",
                    f"{what} could reach machine {AXES[i]} {_n(bad)} mm, outside the profile"
                    f" envelope {env.describe(i)}. Choose a target inside the envelope"
                    " (machine coordinates; status shows the current MPos).",
                    n,
                )

    def low_rapid(self, n: int, start: Span, target: Span, moved: set[int]) -> None:
        sz = self.lim.safe_z
        if sz is None or not ({0, 1} & moved):
            return
        lowest = min(start[2][0], target[2][0])
        if lowest < sz - EPS:
            self.c.refuse(
                "rapid_below_safe_z",
                f"rapid X/Y move with the spindle down to machine Z {_n(lowest)}, below the"
                f" safe height {_n(sz)} (profile safe_z_machine). Retract first"
                f" (move z={_n(sz)}), then move X/Y; or give a feed for a slow move.",
                n,
            )


def check_motion(
    lines: Sequence[str],
    machine: Machine,
    limits: Limits,
    *,
    expect: str | None,
    start_modal: Modal | None = None,
    allow_spindle: bool = False,
    advisory: bool = False,
) -> Check:
    """Check one tool call's lines before anything is sent.

    ``advisory`` checks only the lines themselves (envelope, unsupported codes) and skips the
    machine-state, authority, expect and modal-restore rules; it reviews a resume preamble
    that the bridge, not Claude, will send.
    """
    lines = [str(x) for x in lines]
    modal = replace(start_modal) if start_modal is not None else Modal(known=False)
    check = Check(lines=lines, start_modal=replace(modal))
    if not lines or not any(normalize(ln) for ln in lines):
        check.refuse("empty", "no lines to send")
        return check
    if len(lines) > MAX_LINES:
        check.refuse(
            "line_count",
            f"{len(lines)} lines in one call; at most {MAX_LINES}. For a program, use"
            " run_file (the operator confirms it in UGS).",
        )
        return check
    if not advisory and not (expect or "").strip():
        check.refuse(
            "expect",
            "state the expected outcome in `expect` (for example 'probe touches the BitZero"
            " within 10 mm'); it is logged with the result.",
        )
    if start_modal is None and not advisory:
        check.notes.append("The controller's modal state wasn't read; assumed " + modal.words())

    # "?" is a real-time status query, not G-code; blank it so the parser doesn't flag it.
    prog = interpret(lex("\n".join("" if normalize(ln) == "?" else ln for ln in lines)))
    for issue in prog.issues:
        if issue.code == "syntax" and issue.level == "error":
            check.refuse("syntax", issue.message.split(": ", 1)[-1], issue.line)
    for code, nums in prog.unsupported.items():
        if code in POLICY_REFUSED_G:
            continue
        reason = next((i.message for i in prog.issues if i.code == code), f"{code}: unsupported")
        for n in nums:
            check.refuse(
                "unsupported",
                f"{reason.split(': ', 1)[-1]}; GRBL 1.1 would reject or mis-run it.",
                n,
            )
    for tc in prog.tool_changes:
        check.refuse(
            "tool_change",
            "M6 (tool change) is never sent by Claude: tool changes are confirmed by the"
            " operator in UGS (SPEC §11.5). Ask the operator to change the tool (notify), then"
            " measure it on the BitSetter with probe.",
            tc.line,
        )

    ctx = _Ctx(check, machine, limits, modal, advisory)
    for n, raw in enumerate(lines, start=1):
        _line(ctx, n, raw, allow_spindle=allow_spindle)
        check.modal_after.append(replace(ctx.modal))

    check.end_modal = replace(ctx.modal)
    check.end_span = ctx.span
    check.end_wco = (ctx.wco[0], ctx.wco[1], ctx.wco[2]) if ctx.wco else None

    if check.confirm and len([ln for ln in lines if normalize(ln)]) > 1:
        check.refuse(
            "confirm_alone",
            f"{check.confirm} needs the operator's confirmation in UGS and must be sent on its"
            " own, in a call with no other lines.",
        )
    if not advisory:
        _machine_rules(check, machine, limits)
        left = ctx.modal.leftovers()
        if left:
            words = ", ".join(MODE_WORDS.get(v, v) for _, v in left)
            fix = " ".join(FIX_WORD[k] for k, _ in left)
            check.refuse(
                "modal_restore",
                f"the call would leave {words} active, and later commands (UGS's, the"
                f" operator's, the next file) would be read in that mode. End the lines with"
                f" '{fix}'.",
            )
    return check


def _machine_rules(check: Check, m: Machine, limits: Limits) -> None:
    needs_motion = any(
        normalize(ln) and classify_line(ln).level is Level.MOTION for ln in check.lines
    )
    if m.locked:
        check.refuse("authority", "UGS access is locked by the operator")
        return
    if needs_motion and not level_allows(m.level, Level.MOTION):
        check.refuse(
            "authority",
            f"this needs Motion authority and the operator has granted {m.level}. Only the"
            " operator can arm Motion, in the UGS bridge panel: ask with"
            " request_authority(level='motion', reason=...).",
        )
    if needs_motion and not m.connected:
        check.refuse("connection", "UGS is not connected to the controller.")
        return
    if needs_motion and m.report_age_s is not None and m.report_age_s >= STALE_REPORT_S:
        check.refuse(
            "connection",
            f"no status report from the controller for {m.report_age_s:.0f} s: it may be frozen"
            " or disconnected, and its position can't be trusted. The operator should check the"
            " controller and USB, reconnect in UGS and home.",
        )
        return
    if check.confirm or not (check.moves_machine or check.sets_offset):
        return  # $H / $X are allowed in alarm (the operator confirms); modal-only lines too
    if m.stream not in ("idle", "completed", "error"):
        check.refuse(
            "busy", f"a file stream is {m.stream}; wait for it to finish, or hold/reset first."
        )
    if m.interceptor:
        check.refuse(
            "busy", "the tool-change interceptor is active; the operator must finish it first."
        )
    if m.state == "Alarm":
        check.refuse(
            "state",
            f"the controller is in alarm ({m.alarm or 'locked'}). The operator must clear it:"
            " home ($H) or unlock ($X), both confirmed in UGS. You can send ['$H'] on its own"
            " to ask for homing.",
        )
    elif m.state != "Idle":
        hint = {
            "Hold": "use resume to continue or reset to abandon the motion",
            "Door": "the safety door is open",
            "Home": "homing is in progress",
        }.get(str(m.state), "wait until it is Idle")
        check.refuse("state", f"the controller is {m.state}, not Idle: {hint}.")
    if not m.homed:
        check.refuse(
            "homed",
            "the machine hasn't been homed since UGS connected, so machine coordinates can't"
            " be trusted (connecting resets the controller). Ask the operator to home, or send"
            " ['$H'] on its own (the operator confirms it in UGS).",
        )
    if m.mpos is None:
        check.refuse("position", "the machine position is unknown (no status report yet).")
    if limits.envelope is None and check.moves_machine:
        check.refuse(
            "envelope",
            "the machine profile has no envelope_mm, so targets can't be checked. Fix the"
            " profile (bridge/profiles) first.",
        )


def _line(ctx: _Ctx, n: int, raw: str, *, allow_spindle: bool) -> None:
    c = ctx.c
    if normalize(raw) == "?":
        return
    ln = lex_line(n, raw)
    if ln.system is not None:
        _system(ctx, n, ln.system)
        return
    if not ln.words:
        return
    gs: list[str] = []
    ms: list[str] = []
    vals: dict[str, float] = {}
    for letter, value in ln.words:
        if letter == "G":
            gs.append(code_name("G", value))
        elif letter == "M":
            ms.append(code_name("M", value))
        else:
            vals[letter] = value
    for g in gs:
        if g in POLICY_REFUSED_G:
            c.refuse("unsupported", f"{g}: {POLICY_REFUSED_G[g]}.", n)
    for mcode in ms:
        if mcode in SPINDLE_START and not allow_spindle:
            c.refuse("spindle", f"{mcode} starts the router (BitRunner). {SPINDLE_REFUSAL}", n)
        elif mcode in PROGRAM_FLOW and not ctx.advisory:
            c.refuse(
                "program_flow",
                f"{mcode} is a program pause/end code (it also resets modes); not for"
                " interactive commands. Use hold, resume or reset instead.",
                n,
            )
        elif mcode == "M56":
            c.refuse("unsupported", "M56 (parking override) is not available to Claude.", n)
    if vals.get("S", 0.0) > 0 and not allow_spindle:
        c.refuse(
            "spindle",
            f"S{_n(vals['S'])} sets the spindle speed, which only matters with the router on"
            f" (and can switch it on). {SPINDLE_REFUSAL}",
            n,
        )

    md = ctx.modal
    for g in gs:
        if g in ("G20", "G21"):
            md.units = g
        elif g in ("G90", "G91"):
            md.distance = g
        elif g in ("G93", "G94"):
            md.feed_mode = g
        elif g in PLANES:
            md.plane = g
        elif g == "G54":
            md.wcs = g
    scale = MM_PER_INCH if md.units == "G20" else 1.0
    if "F" in vals:
        md.feed = vals["F"] if md.feed_mode == "G93" else vals["F"] * scale
        if md.feed_mode == "G94" and md.feed > ctx.lim.feed_max + EPS:
            c.refuse(
                "feed_limit",
                f"feed {_n(md.feed)} mm/min is above the machine's rapid rate"
                f" {_n(ctx.lim.feed_max)} mm/min (profile rapid_mm_min).",
                n,
            )

    axes: list[float | None] = [vals.get(a) for a in AXES]
    if "G10" in gs:
        _g10(ctx, n, vals, axes, scale)
        return
    for g in gs:
        if g in MOTION_MODES:
            md.motion = g
    if all(v is None for v in axes):
        if "G53" in gs:
            c.refuse("syntax", "G53 without axis words", n)
        return
    if md.motion == "G80":
        c.refuse("syntax", "axis words with no motion mode (G80)", n)
        return
    c.moves_machine = True
    if ctx.span is None:
        return
    start = [list(a) for a in ctx.span]
    machine_coords = "G53" in gs
    if machine_coords and md.motion not in ("G0", "G1"):
        c.refuse("syntax", "G53 works only with G0 or G1", n)
        return
    if md.motion.startswith("G38"):
        _probe(ctx, n, vals, axes, scale, start)
        return
    if md.motion in ("G1", "G2", "G3") and (md.feed is None or md.feed <= 0):
        c.refuse("feed_limit", f"{md.motion} with no feed rate (add F...; GRBL error:22)", n)
    target = _target(ctx, n, axes, scale, start, machine_coords)
    if target is None:
        return
    if md.motion in ("G2", "G3"):
        box = _arc_box(ctx, n, vals, scale, start, target)
        if box is None:
            return
        lo, hi, length = box
        kind = "arc"
        ctx.in_envelope(n, lo, hi, "this arc")
    else:
        # A straight move between two points inside the (convex) envelope stays inside it;
        # the start was checked by the line before (or is where the machine is now).
        lo, hi = _hull(start, target)
        length = math.dist([s[0] for s in start], [t[0] for t in target])
        kind = "rapid" if md.motion == "G0" else "feed"
        if kind == "rapid":
            ctx.low_rapid(n, start, target, {i for i, v in enumerate(axes) if v is not None})
        moved = [i for i, v in enumerate(axes) if v is not None]
        ctx.in_envelope(n, *_ends(target), "this move", moved)
    rate = None if kind == "rapid" or md.feed_mode == "G93" else md.feed
    c.moves.append(Planned(n, kind, lo, hi, length, rate))
    ctx.span = target


def _target(
    ctx: _Ctx,
    n: int,
    axes: Sequence[float | None],
    scale: float,
    start: Span,
    machine_coords: bool,
    distance: str | None = None,
) -> Span | None:
    dist = distance or ctx.modal.distance
    out = [list(a) for a in start]
    for i, v in enumerate(axes):
        if v is None:
            continue
        mm = v * scale
        if machine_coords:
            out[i] = [mm, mm]
        elif dist == "G91":
            out[i] = [start[i][0] + mm, start[i][1] + mm]
        elif ctx.wco is None:
            ctx.c.refuse(
                "position",
                "the work offset (WCO) is unknown, so a work-coordinate target can't be"
                " checked. Use machine coordinates (move with machine=true, or G53).",
                n,
            )
            return None
        else:
            out[i] = [mm + ctx.wco[i], mm + ctx.wco[i]]
    return out


def _arc_box(
    ctx: _Ctx, n: int, vals: dict[str, float], scale: float, start: Span, target: Span
) -> tuple[Vec3, Vec3, float] | None:
    a0, a1, lin = PLANES[ctx.modal.plane]
    s0, s1 = _exact(start, a0), _exact(start, a1)
    if s0 is None or s1 is None:
        ctx.c.refuse(
            "position",
            "an arc can't start from an uncertain position (after a probe in the same call);"
            " move to an absolute position first.",
            n,
        )
        return None
    if "R" in vals:
        # The centre is within |R| of the start, so the arc stays within 2|R| of it.
        r = abs(vals["R"]) * scale
        box0, box1 = (s0 - 2 * r, s0 + 2 * r), (s1 - 2 * r, s1 + 2 * r)
    else:
        c0 = s0 + vals.get("IJK"[a0], 0.0) * scale
        c1 = s1 + vals.get("IJK"[a1], 0.0) * scale
        r = math.hypot(s0 - c0, s1 - c1)
        box0, box1 = (c0 - r, c0 + r), (c1 - r, c1 + r)
    lo = [0.0, 0.0, 0.0]
    hi = [0.0, 0.0, 0.0]
    # The circle's box, plus the target in case it isn't on the circle (GRBL error:33).
    lo[a0], hi[a0] = min(box0[0], target[a0][0]), max(box0[1], target[a0][1])
    lo[a1], hi[a1] = min(box1[0], target[a1][0]), max(box1[1], target[a1][1])
    lo[lin] = min(start[lin][0], target[lin][0])
    hi[lin] = max(start[lin][1], target[lin][1])
    return (lo[0], lo[1], lo[2]), (hi[0], hi[1], hi[2]), math.pi * r


def _probe(
    ctx: _Ctx,
    n: int,
    vals: dict[str, float],
    axes: Sequence[float | None],
    scale: float,
    start: Span,
) -> None:
    c = ctx.c
    c.probes += 1
    if c.probes > 1:
        c.refuse(
            "probe_one",
            "one probe per call, so the probe input can be checked clear before each probe."
            " Send the second (slow) pass as another call.",
            n,
        )
    if ctx.modal.distance != "G91":
        c.refuse(
            "probe_relative",
            "probes must be relative: put G91 on the probe line (and G90 after it), so the"
            " travel is bounded from where the tool is.",
            n,
        )
        return
    moving = [(i, v) for i, v in enumerate(axes) if v is not None and abs(v) > EPS]
    if len(moving) != 1:
        c.refuse("probe_axis", "a probe moves exactly one axis (Z, or X/Y with a known bore).", n)
        return
    axis, dist = moving[0]
    mm = dist * scale
    if axis == 2 and mm > 0:
        c.refuse("probe_axis", "a Z probe goes down (negative Z).", n)
    if axis in (0, 1) and not ctx.lim.xy_probe:
        c.refuse("probe_axis", ctx.lim.xy_probe_reason, n)
    if abs(mm) > ctx.lim.probe_max + EPS:
        c.refuse(
            "probe_bounded",
            f"probe travel {_n(abs(mm))} mm exceeds the {_n(ctx.lim.probe_max)} mm limit. Move"
            " closer first (move or jog), then probe.",
            n,
        )
    if "F" not in vals:
        c.refuse("probe_feed", "give the probe feed on the probe line (F...).", n)
    elif ctx.modal.feed is not None and ctx.modal.feed > ctx.lim.probe_feed_max + EPS:
        c.refuse(
            "probe_feed",
            f"probe feed {_n(ctx.modal.feed)} mm/min is above {_n(ctx.lim.probe_feed_max)}"
            " mm/min (the profile's fastest probe feed).",
            n,
        )
    if not ctx.advisory:
        pin = ctx.m.probe_pin
        if pin is None:
            c.refuse("probe_clear", "the probe input state is unknown (no status report).", n)
        elif pin:
            c.refuse(
                "probe_clear",
                "the probe input is already active (Pn:P) before probing: the tool may be"
                " resting on the plate, or the BitZero clip is touching it. Retract (move z"
                " up) or ask the operator to check the clip, then probe again.",
                n,
            )
    target = [list(a) for a in start]
    target[axis] = [start[axis][0] + mm, start[axis][1] + mm]
    lo, hi = _hull(start, target)
    ctx.in_envelope(n, *_ends(target), "the probe", [axis])
    c.moves.append(Planned(n, "probe", lo, hi, abs(mm), ctx.modal.feed))
    # Contact can happen anywhere between the start and the probe target.
    after = [list(a) for a in start]
    after[axis] = [lo[axis], hi[axis]]
    ctx.span = after


def _g10(
    ctx: _Ctx, n: int, vals: dict[str, float], axes: Sequence[float | None], scale: float
) -> None:
    c = ctx.c
    c.sets_offset = True
    lnum = vals.get("L")
    pnum = vals.get("P", 0.0)
    if lnum not in (2.0, 20.0):
        c.refuse(
            "unsupported", "only G10 L2 / L20 (work offsets) are allowed; use set_work_offset.", n
        )
        return
    if pnum not in (0.0, 1.0):
        c.refuse("unsupported", "the bridge tracks only G54 (G10 P1, or P0 = active).", n)
        return
    new = list(ctx.wco) if ctx.wco is not None else [0.0, 0.0, 0.0]
    for i, v in enumerate(axes):
        if v is None:
            continue
        if lnum == 2.0:
            new[i] = v * scale
            continue
        pos = _exact(ctx.span, i) if ctx.span is not None else None
        if pos is None:
            c.refuse(
                "position",
                "G10 L20 needs an exact current position (not right after a probe in the same"
                " call); use G10 L2 with the machine value instead.",
                n,
            )
            return
        new[i] = pos - v * scale
    env = ctx.lim.envelope
    if env is not None:
        for i, v in enumerate(axes):
            if v is not None and not (env.lo[i] - EPS <= new[i] <= env.hi[i] + EPS):
                c.refuse(
                    "envelope",
                    f"the new work zero would be at machine {AXES[i]} {_n(new[i])}, outside the"
                    f" envelope {env.describe(i)}. A work zero must be a reachable point.",
                    n,
                )
    ctx.wco = new


def _system(ctx: _Ctx, n: int, line: str) -> None:
    c = ctx.c
    cls = classify_line(line)
    if cls.cls is LineClass.QUERY:
        return
    if cls.cls is LineClass.CONFIRM:
        c.confirm = cls.line
        return
    if cls.refused_by_default:
        c.refuse(
            "setting_write",
            "this writes a controller setting or startup block; Claude never changes $"
            " settings. Tell the operator what you would change and why (notify).",
            n,
        )
        return
    if line.startswith("$J="):
        _jog(ctx, n, line[3:])
        return
    c.refuse(
        "system_cmd",
        f"'{line}' is not available to Claude (only ?, $$, $#, $G, $I, $N, $J=..., and $H or"
        " $X with the operator's confirmation).",
        n,
    )


def _jog(ctx: _Ctx, n: int, body: str) -> None:
    c = ctx.c
    ln = lex_line(n, body)
    if ln.problems or not ln.words:
        c.refuse("syntax", "unreadable jog ($J=...)", n)
        return
    units, dist, machine_coords = ctx.modal.units, ctx.modal.distance, False
    vals: dict[str, float] = {}
    for letter, value in ln.words:
        if letter == "G":
            g = code_name("G", value)
            if g in ("G20", "G21"):
                units = g
            elif g in ("G90", "G91"):
                dist = g
            elif g == "G53":
                machine_coords = True
            else:
                c.refuse("syntax", f"{g} is not allowed in a jog (GRBL error:16)", n)
                return
        elif letter in "XYZF":
            vals[letter] = value
        else:
            c.refuse("syntax", f"'{letter}' is not allowed in a jog (GRBL error:16)", n)
            return
    scale = MM_PER_INCH if units == "G20" else 1.0
    feed = vals.get("F")
    if feed is None or feed <= 0:
        c.refuse("feed_limit", "a jog needs a feed (F...)", n)
        return
    if feed * scale > ctx.lim.feed_max + EPS:
        c.refuse(
            "feed_limit",
            f"jog feed {_n(feed * scale)} mm/min is above {_n(ctx.lim.feed_max)} mm/min.",
            n,
        )
    c.moves_machine = True
    if ctx.span is None:
        return
    axes = [vals.get(a) for a in AXES]
    start = [list(a) for a in ctx.span]
    target = _target(ctx, n, axes, scale, start, machine_coords, distance=dist)
    if target is None:
        return
    lo, hi = _hull(start, target)
    ctx.in_envelope(n, *_ends(target), "the jog", [i for i, v in enumerate(axes) if v is not None])
    length = math.dist([s[0] for s in start], [t[0] for t in target])
    c.moves.append(Planned(n, "jog", lo, hi, length, feed * scale))
    ctx.span = target

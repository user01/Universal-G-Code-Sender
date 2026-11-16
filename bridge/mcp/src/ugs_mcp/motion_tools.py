"""Claude's motion, origin, job and stream tools (SPEC §5.1, §11.8), work package H.

Every tool that sends G-code reads the bridge status (and ``$G`` for the modal state), runs
:func:`ugs_mcp.safety.check_motion` on the exact lines it will send, and refuses with the
checker's message if anything fails, before a byte goes out. Then it queues the lines with
``POST /command`` (with ``expect``, which the bridge logs), waits for the command to finish
and the controller to report Idle, and returns each line's reply plus the final position.

If a call fails half way (an error or an alarm) while a non-default mode such as G91 is
active, the tool restores the defaults: with a ``G21 G90 G94 G17`` line when the controller
still accepts G-code, or with a soft reset when it is in alarm (G-code is locked out then).
"""

from __future__ import annotations

import math
import re
from collections.abc import Awaitable, Callable, Sequence
from pathlib import Path
from typing import Any

import anyio
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from ugs_mcp.bridge_client import TERMINAL_COMMAND_STATES, BridgeClient, BridgeError
from ugs_mcp.describe import ALARMS, ERRORS, pins_words
from ugs_mcp.gcode.analyze import analyze_path
from ugs_mcp.safety import (
    Check,
    Level,
    Limits,
    Machine,
    Modal,
    check_motion,
    fmt_num,
    level_allows,
)

ProfileGetter = Callable[[], Awaitable[tuple[dict[str, Any] | None, str | None]]]

JOG_MAX_MM = 100.0
RESTORE_LINE = "G21 G90 G94 G17"
_PRB = re.compile(r"\[PRB:([-\d.]+),([-\d.]+),([-\d.]+):([01])\]")
_G54 = re.compile(r"\[G54:([-\d.]+),([-\d.]+),([-\d.]+)\]")
VERIFY_TOL = 0.01
"""SPEC §11.3: an applied origin must read back from ``$#`` within 0.01 mm."""


def _xyz(v: Sequence[float] | None) -> str:
    if not v:
        return "unknown"
    return " ".join(f"{a}{fmt_num(float(x))}" for a, x in zip("XYZ", v, strict=False))


async def _guard[T](aw: Awaitable[T]) -> T:
    try:
        return await aw
    except BridgeError as exc:
        raise ToolError(exc.message) from None


class Motion:
    """Shared plumbing: status, modal state, checking, sending and waiting."""

    def __init__(self, bridge: BridgeClient, profile: ProfileGetter) -> None:
        self.bridge = bridge
        self.profile = profile

    async def context(self) -> tuple[dict[str, Any], Machine, Limits]:
        st = await _guard(self.bridge.status())
        prof, source = await self.profile()
        return st, Machine.from_status(st), Limits.from_profile(prof, source)

    async def read_modal(self, machine: Machine) -> Modal | None:
        """``$G`` (a Query-class line). None if it can't be read."""
        if not machine.connected or not level_allows(machine.level, Level.QUERY):
            return None
        try:
            acc = await self.bridge.command(["$G"], tag="modal", expect="modal state")
            res = await self.bridge.wait_command(str(acc["id"]), timeout=5.0)
        except BridgeError:
            return None
        text = "\n".join(
            str(x) for ln in res.get("lines") or [] for x in (ln.get("response") or [])
        )
        return Modal.parse_gc(text)

    async def checked(
        self,
        lines: Sequence[str],
        *,
        expect: str | None,
        read_modal: bool = True,
    ) -> tuple[Check, Machine, Limits]:
        _, machine, limits = await self.context()
        modal: Modal | None = None
        pre = check_motion(lines, machine, limits, expect=expect)
        if any(r.rule in ("authority", "connection") for r in pre.refusals):
            raise ToolError(pre.message())  # don't even read $G without Motion authority
        if read_modal:
            modal = await self.read_modal(machine)
        check = check_motion(lines, machine, limits, expect=expect, start_modal=modal)
        if not check.ok:
            raise ToolError(check.message())
        return check, machine, limits

    async def wait_result(self, cid: str, timeout: float) -> dict[str, Any]:
        deadline = anyio.current_time() + timeout
        while True:
            res = await _guard(self.bridge.command_result(cid))
            state = res.get("state")
            if state in TERMINAL_COMMAND_STATES or state == "awaiting_operator":
                return res
            if anyio.current_time() >= deadline:
                return res
            await anyio.sleep(0.03)

    async def wait_idle(self, timeout: float) -> dict[str, Any]:
        deadline = anyio.current_time() + timeout
        while True:
            st = await _guard(self.bridge.status())
            ctrl = st.get("controller") or {}
            if ctrl.get("state") in ("Idle", "Alarm", "Door") or anyio.current_time() >= deadline:
                return st
            await anyio.sleep(0.03)

    async def run(
        self,
        check: Check,
        limits: Limits,
        *,
        tag: str,
        expect: str,
        timeout_s: float | None = None,
    ) -> tuple[dict[str, Any], dict[str, Any], list[str]]:
        """Send checked lines; return (command result, final status, notes)."""
        est = check.estimate_s(limits.feed_max)
        timeout = timeout_s or min(600.0, 20.0 + 1.5 * est)
        acc = await _guard(
            self.bridge.command(check.lines, tag=tag, expect=expect, timeout_s=timeout)
        )
        cid = str(acc["id"])
        res = await self.wait_result(cid, timeout)
        notes: list[str] = []
        if res.get("state") == "awaiting_operator":
            st = await _guard(self.bridge.status())
            return res, st, notes
        if res.get("state") not in TERMINAL_COMMAND_STATES:
            notes.append(
                f"Command {cid} is still {res.get('state')} after {timeout:.0f} s; check status"
                " and console."
            )
        st = await self.wait_idle(timeout)
        if res.get("state") in ("failed", "dropped"):
            notes.extend(await self.recover(check, res, st))
            st = await _guard(self.bridge.status())
        state = (st.get("controller") or {}).get("state")
        if res.get("state") == "done" and state not in ("Idle", None):
            notes.append(f"The controller is {state}, not Idle, after {timeout:.0f} s.")
        return res, st, notes

    async def recover(self, check: Check, res: dict[str, Any], st: dict[str, Any]) -> list[str]:
        """After a failure, don't leave G91/G20/G93 behind (SPEC §5.2)."""
        lines = res.get("lines") or []
        bad = next((ln for ln in lines if ln.get("state") not in ("ok",)), None)
        if bad is None:
            return []
        idx = int(bad.get("index", 0)) + 1
        modal = check.modal_after[idx - 1] if bad.get("state") == "alarm" else None
        if modal is None:
            modal = check.modal_before(idx)
        if modal.safe:
            return []
        left = " ".join(v for _, v in modal.leftovers())
        in_alarm = (st.get("controller") or {}).get("state") == "Alarm"
        if in_alarm:
            await _guard(self.bridge.realtime("reset"))
            return [
                f"The failure left {left} active and the controller is in alarm (G-code is"
                " locked out), so I sent a soft reset: GRBL is back in G21 G90 G94 G17. It"
                " stays in alarm until the operator unlocks ($X) or homes ($H) in UGS."
            ]
        try:
            acc = await self.bridge.command(
                [RESTORE_LINE], tag="restore-modal", expect="modal state back to defaults"
            )
            r2 = await self.bridge.wait_command(str(acc["id"]), timeout=5.0)
        except BridgeError as exc:
            return [f"The failure left {left} active and restoring failed: {exc.message}"]
        if r2.get("state") == "done":
            return [f"The failure left {left} active; I sent '{RESTORE_LINE}' to restore it."]
        return [f"The failure left {left} active; '{RESTORE_LINE}' did not complete."]


def lines_text(res: dict[str, Any]) -> list[str]:
    out = []
    for ln in res.get("lines") or []:
        state = ln.get("state")
        resp = [str(x) for x in ln.get("response") or [] if str(x) != "ok"]
        detail = state
        if ln.get("error_code") is not None:
            code = int(ln["error_code"])
            detail = f"error:{code} ({ERRORS.get(code, 'unknown error')})"
        elif ln.get("alarm_code") is not None:
            code = int(ln["alarm_code"])
            detail = f"ALARM:{code} ({ALARMS.get(code, 'unknown alarm')})"
        if len(resp) > 3:
            keep = [r for r in resp if r.startswith(("[G54:", "[PRB:"))] or resp[:3]
            resp = [*keep, f"(+{len(resp) - len(keep)} more lines)"]
        extra = f"  {' '.join(resp)}" if resp and ln.get("error_code") is None else ""
        out.append(f"  {int(ln.get('index', 0)) + 1}. {ln.get('line')}  -> {detail}{extra}")
    return out


def now_text(st: dict[str, Any]) -> str:
    c = st.get("controller") or {}
    auth = st.get("authority") or {}
    parts = [
        f"Now: {c.get('state', 'unknown')}",
        f"MPos {_xyz(c.get('mpos'))}",
        f"WPos {_xyz(c.get('wpos'))}",
        pins_words(c.get("pins")),
    ]
    text = ", ".join(parts) + "."
    if not level_allows(str(auth.get("level") or "observe"), Level.MOTION):
        text += f" Claude's authority is now {auth.get('level')}."
    return text


def report(
    head: str,
    res: dict[str, Any],
    st: dict[str, Any],
    notes: Sequence[str],
    check: Check,
    limits: Limits,
    extra: Sequence[str] = (),
) -> str:
    out = [head, f"Sent as {res.get('id')} ({res.get('state')}), expect: {res.get('expect')!r}"]
    out.extend(lines_text(res))
    out.extend(extra)
    out.extend(notes)
    out.extend(check.notes)
    out.append(now_text(st))
    if limits.assumptions and check.moves:
        out.append("Assumptions: " + "; ".join(limits.assumptions) + ".")
    return "\n".join(out)


def find_prb(res: dict[str, Any]) -> tuple[tuple[float, float, float], bool] | None:
    for ln in res.get("lines") or []:
        for r in ln.get("response") or []:
            if m := _PRB.search(str(r)):
                pos = (float(m.group(1)), float(m.group(2)), float(m.group(3)))
                return pos, m.group(4) == "1"
    return None


def find_g54(res: dict[str, Any]) -> tuple[float, float, float] | None:
    for ln in res.get("lines") or []:
        for r in ln.get("response") or []:
            if m := _G54.search(str(r)):
                return (float(m.group(1)), float(m.group(2)), float(m.group(3)))
    return None


def _awaiting(res: dict[str, Any], what: str) -> str:
    return (
        f"{what} is waiting for the operator's confirmation in UGS (command {res.get('id')})."
        " Nothing runs until they confirm; follow it with status or console."
    )


def register_motion_tools(mcp: MCPServer, bridge: BridgeClient, profile: ProfileGetter) -> None:
    mo = Motion(bridge, profile)

    @mcp.tool()
    async def move(
        x: float | None = None,
        y: float | None = None,
        z: float | None = None,
        machine: bool = True,
        feed: float | None = None,
        expect: str | None = None,
    ) -> str:
        """One straight move (Motion authority). Machine coordinates (G53) by default; with
        machine=false, work coordinates (G54). Rapid unless `feed` (mm/min) is given. Checked
        against the profile envelope; rapid X/Y moves below the profile's safe Z are refused
        (retract first). Needs the machine homed and Idle. Waits until Idle and returns the
        reply and the new position. `expect` (logged) defaults to the target."""
        axes = {a: v for a, v in zip("XYZ", (x, y, z), strict=True) if v is not None}
        if not axes:
            raise ToolError("give at least one of x, y, z")
        words = " ".join(f"{a}{fmt_num(v)}" for a, v in axes.items())
        motion = f"G1 {words} F{fmt_num(feed)}" if feed else f"G0 {words}"
        line = f"G21 G90 G94 {'G53 ' if machine else ''}{motion}"
        frame = "machine" if machine else "work"
        exp = expect or f"move to {frame} {words}"
        check, _, limits = await mo.checked([line], expect=exp)
        res, st, notes = await mo.run(check, limits, tag="move", expect=exp)
        return report(f"Move to {frame} {words}.", res, st, notes, check, limits)

    @mcp.tool()
    async def jog(axis: str, distance: float, feed: float, expect: str | None = None) -> str:
        """Jog one axis by `distance` mm at `feed` mm/min with `$J=` (Motion authority).
        At most 100 mm per call, checked against the envelope; hold cancels a jog. Waits
        until Idle."""
        ax = axis.strip().upper()
        if ax not in ("X", "Y", "Z"):
            raise ToolError("axis must be X, Y or Z")
        if not distance or abs(distance) > JOG_MAX_MM:
            raise ToolError(f"distance must be non-zero and at most {JOG_MAX_MM:g} mm per jog")
        if feed <= 0:
            raise ToolError("feed must be positive (mm/min)")
        line = f"$J=G21 G91 {ax}{fmt_num(distance)} F{fmt_num(feed)}"
        exp = expect or f"jog {ax} {fmt_num(distance)} mm"
        check, _, limits = await mo.checked([line], expect=exp)
        res, st, notes = await mo.run(check, limits, tag="jog", expect=exp)
        return report(f"Jog {ax} {fmt_num(distance)} mm.", res, st, notes, check, limits)

    @mcp.tool()
    async def probe(
        axis: str,
        max_distance: float,
        feed: float,
        retract: float = 2.0,
        expect: str | None = None,
    ) -> str:
        """Probe with G38.2 (Motion authority): relative, at most 25 mm, starting with the
        probe input clear. axis: "Z" (down), or "X+", "X-", "Y+", "Y-" only when the profile
        knows the BitZero bore (ledger B3; until then X/Y is refused). After contact it
        retracts `retract` mm (0 = stay in contact) and restores G90. Returns the [PRB]
        contact in machine and work coordinates. On no contact (ALARM:5) it soft-resets so
        GRBL is back in G90, and the operator must unlock. One probe per call: for a slow
        second pass, call again."""
        spec = axis.strip().upper()
        dirs = {"Z": ("Z", -1), "Z-": ("Z", -1), "X+": ("X", 1), "X-": ("X", -1)}
        dirs |= {"Y+": ("Y", 1), "Y-": ("Y", -1)}
        if spec not in dirs:
            raise ToolError("axis must be Z (down), X+, X-, Y+ or Y-")
        ax, sign = dirs[spec]
        if max_distance <= 0:
            raise ToolError("max_distance is the travel in mm and must be positive")
        if retract < 0:
            raise ToolError("retract must be >= 0")
        lines = [f"G21 G91 G38.2 {ax}{fmt_num(sign * max_distance)} F{fmt_num(feed)}"]
        if retract > 0:
            # Z retracts are rapids; X/Y retracts (inside a bore, below safe Z) are fed.
            back = f"{ax}{fmt_num(-sign * retract)}"
            lines.append(f"G0 {back}" if ax == "Z" else f"G1 {back} F{fmt_num(feed)}")
        lines.append("G90")
        exp = expect or f"probe {spec} within {fmt_num(max_distance)} mm"
        check, machine, limits = await mo.checked(lines, expect=exp)
        res, st, notes = await mo.run(check, limits, tag="probe", expect=exp)
        extra: list[str] = []
        hit = find_prb(res)
        if hit and hit[1]:
            (px, py, pz), _ = hit
            wco = machine.wco or (0.0, 0.0, 0.0)
            work = (px - wco[0], py - wco[1], pz - wco[2])
            extra.append(
                f"Contact at machine {_xyz((px, py, pz))} (work {_xyz(work)} with the current"
                f" WCO {_xyz(wco)})."
            )
            bs = limits.bitsetter_xy
            at_setter = bs is not None and math.hypot(px - bs[0], py - bs[1]) <= 5.0
            if ax == "Z" and at_setter and bs is not None:
                extra.append(
                    f"This is the BitSetter position (profile X{fmt_num(bs[0])} Y{fmt_num(bs[1])},"
                    f" assumed; ledger B6): tool trigger at machine Z {fmt_num(pz)}. Re-measuring"
                    " the same tool should repeat within 0.05 mm (ledger B8)."
                )
            elif ax == "Z" and limits.plate_thickness is not None:
                status = "verified" if limits.plate_verified else "sourced, NOT measured"
                plate = limits.plate_thickness
                extra.append(
                    f"If this touched the BitZero (plate {fmt_num(plate)} mm, {status};"
                    f" ledger B1): set_work_offset(z={fmt_num(pz - plate)}) makes the stock"
                    " top Z0."
                )
        elif res.get("state") == "done":
            extra.append("No [PRB] reply was seen.")
        else:
            failed = next(
                (ln for ln in res.get("lines") or [] if ln.get("alarm_code") is not None), None
            )
            if failed is not None and failed["alarm_code"] == 5:
                extra.append(
                    f"No contact within {fmt_num(max_distance)} mm. Check the clip and the plate"
                    " placement, move closer, and probe again."
                )
            elif failed is not None and failed["alarm_code"] == 4:
                extra.append("The probe input was already active when the probe started.")
        return report(f"Probe {spec}.", res, st, notes, check, limits, extra)

    @mcp.tool()
    async def send(lines: list[str], expect: str, timeout_s: float | None = None) -> str:
        """Send raw lines (Motion authority) after the SPEC §5.2 checks: parsed with modal
        state tracked from the controller's $G; the call must end in G21 G90 G94 G17 (restore
        G91/G20/G93 in the same call); every target inside the profile envelope; homed and
        Idle before motion; probes relative, single-axis, <= 25 mm, input clear, one per call;
        no M6, M3/M4, M0/M1/M2/M30, G28/G30/G92, other work systems, setting writes ($n=,
        $N..=, $RST) or unsupported codes. `expect` (required) is the outcome you expect; it
        is logged. $H or $X alone waits for the operator's confirmation in UGS."""
        if not isinstance(lines, list) or not all(isinstance(x, str) for x in lines):
            raise ToolError("lines must be a list of strings")
        check, _, limits = await mo.checked(lines, expect=expect)
        res, st, notes = await mo.run(check, limits, tag="send", expect=expect, timeout_s=timeout_s)
        if res.get("state") == "awaiting_operator":
            return _awaiting(res, check.confirm or "The command")
        extra: list[str] = []
        if res.get("state") == "done" and check.moves:
            after = await mo.read_modal(Machine.from_status(st))
            if after is not None and not after.safe:
                extra.append(f"WARNING: $G reports {after.words()} after the call.")
        return report(f"Sent {len(lines)} line(s).", res, st, notes, check, limits, extra)

    @mcp.tool()
    async def set_work_offset(
        x: float | None = None,
        y: float | None = None,
        z: float | None = None,
        expect: str | None = None,
    ) -> str:
        """Set the G54 work offset with G10 L2 P1 (Motion authority), then read $# to verify
        it (within 0.01 mm). Values are MACHINE coordinates of the work zero: to make the
        stock top Z0 after a BitZero probe that touched at machine Z c, pass z = c - plate
        thickness. The bridge records the change as an origin."""
        axes = {a: v for a, v in zip("XYZ", (x, y, z), strict=True) if v is not None}
        if not axes:
            raise ToolError("give at least one of x, y, z")
        words = " ".join(f"{a}{fmt_num(v)}" for a, v in axes.items())
        lines = [f"G21 G10 L2 P1 {words}", "$#"]
        exp = expect or f"G54 = {words}"
        check, machine, limits = await mo.checked(lines, expect=exp)
        res, st, notes = await mo.run(check, limits, tag="set-work-offset", expect=exp)
        extra = [f"WCO before: {_xyz(machine.wco)}."]
        g54 = find_g54(res)
        if g54 is None:
            extra.append("Couldn't read G54 back from $#: NOT verified.")
        else:
            bad = [
                f"{a} {fmt_num(g54['XYZ'.index(a)])} (wanted {fmt_num(v)})"
                for a, v in axes.items()
                if abs(g54["XYZ".index(a)] - v) > VERIFY_TOL
            ]
            extra.append(
                f"$# reads G54 {_xyz(g54)}: "
                + ("verified." if not bad else "MISMATCH " + ", ".join(bad) + ".")
            )
        return report(f"Set G54 {words}.", res, st, notes, check, limits, extra)

    async def _realtime(op: str, label: str) -> str:
        sent = await _guard(bridge.realtime(op))
        st: dict[str, Any] = {}
        for _ in range(10):
            await anyio.sleep(0.05)
            st = await _guard(bridge.status())
        return f"{label} sent ({sent.get('sent')}). " + now_text(st)

    @mcp.tool()
    async def hold() -> str:
        """Feed hold (real-time '!'). Always allowed while UGS access is unlocked; also
        cancels a jog. Continue with resume, or abandon with reset."""
        return await _realtime("hold", "Feed hold")

    @mcp.tool()
    async def resume() -> str:
        """Cycle start / resume after a feed hold (real-time '~'). Always allowed while
        unlocked."""
        return await _realtime("resume", "Resume")

    @mcp.tool()
    async def reset() -> str:
        """Soft reset (0x18). Always allowed while unlocked. Stops everything and drops queued
        commands; GRBL returns to G21 G90 G94 G17 G54. A reset while moving loses the
        position (ALARM:3, re-home); the bridge also withdraws Motion authority."""
        text = await _realtime("reset", "Soft reset")
        return text + " Modal state is back to defaults; queued commands were dropped."

    @mcp.tool()
    async def origins(compare: bool = True) -> str:
        """Stored origins (G54 work offsets the bridge recorded: probing, G10, restores),
        newest last, each compared with the current WCO when `compare` is true."""
        data = await _guard(bridge.origins())
        items = data.get("origins") or []
        if not items:
            return "No origins recorded yet."
        wco = None
        if compare:
            st = await _guard(bridge.status())
            wco = Machine.from_status(st).wco
        out = [f"{len(items)} origin(s); current WCO {_xyz(wco)}:" if compare else "Origins:"]
        for o in items:
            vec = [float(v) for v in o.get("wco") or []]
            line = (
                f"- {o.get('id')}"
                + (f" '{o['name']}'" if o.get("name") else "")
                + f": {o.get('wcs', 'G54')} {_xyz(vec)}, {o.get('method')}"
                + (f", tool {o['tool']}" if o.get("tool") else "")
                + (f", ref {fmt_num(float(o['bitsetter_ref']))}" if o.get("bitsetter_ref") else "")
                + f", {o.get('created', '')}"
            )
            if wco and len(vec) == 3:
                d = [a - b for a, b in zip(vec, wco, strict=True)]
                same = all(abs(v) <= VERIFY_TOL for v in d)
                line += " (= current)" if same else f" (differs by {_xyz(d)})"
            out.append(line)
        return "\n".join(out)

    @mcp.tool()
    async def apply_origin(origin_id: str) -> str:
        """Write a stored origin back to G54 (Motion authority; the machine must be homed and
        Idle). The bridge sends G10 L2 P1 and verifies it with $# (within 0.01 mm)."""
        data = await _guard(bridge.origins())
        origin = next((o for o in data.get("origins") or [] if o.get("id") == origin_id), None)
        if origin is None:
            ids = ", ".join(str(o.get("id")) for o in data.get("origins") or []) or "none"
            raise ToolError(f"no origin {origin_id!r} (known: {ids}); see origins")
        vec = [float(v) for v in origin["wco"]]
        words = " ".join(f"{a}{fmt_num(v)}" for a, v in zip("XYZ", vec, strict=True))
        # The same checks as a G10 L2 P1 Claude would send itself.
        check, _, limits = await mo.checked(
            [f"G21 G10 L2 P1 {words}"], expect=f"apply origin {origin_id}", read_modal=False
        )
        acc = await _guard(bridge.apply_origin(origin_id))
        cid = str(acc.get("command_id"))
        res = await mo.wait_result(cid, 15.0)
        st = await _guard(bridge.status())
        extra = []
        g54 = find_g54(res)
        if g54 is None:
            extra.append("Couldn't read G54 back from $#: NOT verified.")
        else:
            ok = all(abs(a - b) <= VERIFY_TOL for a, b in zip(g54, vec, strict=True))
            extra.append(
                f"$# reads G54 {_xyz(g54)}: "
                + ("verified." if ok else f"MISMATCH, wanted {words}.")
            )
        return report(f"Apply origin {origin_id} ({words}).", res, st, [], check, limits, extra)

    @mcp.tool()
    async def job_state() -> str:
        """The current or last job journal: file, stream state, last line acknowledged and
        known done, operation, tool, WCO, BitSetter reference, modal state."""
        js = await _guard(bridge.job_state())
        if js is None:
            return "No job journal yet (nothing has been streamed in this bridge home)."
        f = js.get("file") or {}
        md = js.get("modal") or {}
        out = [
            f"Job {f.get('path')} ({f.get('lines')} lines, sha256 {str(f.get('sha256'))[:12]}…)",
            f"Stream: {js.get('stream')}; last line acknowledged {js.get('line_acked')}, last"
            f" known done {js.get('line_done')} (updated {js.get('updated')}).",
            f"Operation: {js.get('operation') or 'unknown'}; tool {js.get('tool') or 'unknown'}.",
            f"WCO {_xyz(js.get('wco'))}; BitSetter ref {js.get('bitsetter_ref')};"
            f" last MPos {_xyz(js.get('last_mpos'))}.",
        ]
        if md:
            out.append("Modal: " + " ".join(str(v) for v in md.values() if v is not None) + ".")
        out.append("Plan a restart with plan_resume(line | operation | 'stopped').")
        return "\n".join(out)

    @mcp.tool()
    async def plan_resume(target: str) -> str:
        """Ask the bridge for a resume plan (sends nothing). target: a line number ("1234"),
        an operation name or number ("OP 05", "5"), or "stopped" (where the journal says it
        stopped). Returns what the plan requires (home, restore origin, tool measure, ...),
        its checks, the preamble the bridge will send, warnings and the plan_id for
        start_resume. The preamble is also checked against the envelope here."""
        t = target.strip()
        body: dict[str, Any]
        if t.lower() in ("stopped", "where it stopped", "stop"):
            body = {"stopped": True}
        elif t.isdigit():
            body = {"line": int(t)}
        elif t.lower().startswith("op") and t[2:].strip().isdigit():
            body = {"operation": int(t[2:].strip())}
        else:
            body = {"operation": t}
        plan = await _guard(bridge.resume_plan(body))
        out = [
            f"Resume plan {plan.get('plan_id')} for {body}: start at line"
            f" {plan.get('resume_line')}"
            + (" (a safe point)" if plan.get("safe_point") else " (MID-CONTOUR)")
            + f", operation {plan.get('operation') or '?'}, tool {plan.get('tool') or '?'}.",
        ]
        req = plan.get("requires") or []
        out.append("Requires: " + (", ".join(req) if req else "nothing extra") + ".")
        out.append("Checks:")
        for c in plan.get("checks") or []:
            mark = "ok " if c.get("ok") else "NO "
            out.append(f"  [{mark}] {c.get('name')}: {c.get('detail', '')}")
        out.append("Preamble (sent by the bridge after the operator confirms):")
        out.extend(f"  {ln}" for ln in plan.get("preamble") or [])
        warnings = list(plan.get("warnings") or [])
        try:
            _, machine, limits = await mo.context()
            adv = check_motion(
                plan.get("preamble") or [],
                machine,
                limits,
                expect=None,
                allow_spindle=True,
                advisory=True,
            )
            if adv.refusals:
                warnings.extend(
                    "my check: " + r.text(adv.lines) for r in adv.refusals if r.rule != "position"
                )
        except ToolError:
            pass
        if warnings:
            out.append("Warnings:")
            out.extend(f"  - {w}" for w in warnings)
        out.append(
            f"To run it: start_resume('{plan.get('plan_id')}') (Stream authority; the operator"
            " confirms in UGS)."
        )
        return "\n".join(out)

    @mcp.tool()
    async def start_resume(plan_id: str) -> str:
        """Start a resume plan (Stream authority). The operator confirms it in UGS; nothing
        runs before that. Tool changes in it always wait for the operator."""
        _, machine, _ = await mo.context()
        if machine.locked or not level_allows(machine.level, Level.STREAM):
            raise ToolError(
                f"start_resume needs Stream authority (granted: {machine.level}). Only the"
                " operator can grant it in the UGS bridge panel; ask with"
                " request_authority(level='stream', reason=...)."
            )
        await _guard(bridge.resume(plan_id))
        return (
            f"Resume {plan_id} is waiting for the operator's confirmation in UGS. Nothing runs"
            " until they confirm; follow it with status and job_state."
        )

    @mcp.tool()
    async def run_file(path: str) -> str:
        """Load a G-code file into UGS and ask to run it (Stream authority). The operator
        confirms the file (name and SHA-256) in UGS; nothing runs before that. The file is
        analyzed first and the main warnings are returned."""
        p = Path(path).expanduser()
        if not p.is_file():
            raise ToolError(f"{p} is not a readable file")
        _, machine, _ = await mo.context()
        problems = []
        if machine.locked or not level_allows(machine.level, Level.STREAM):
            problems.append(
                f"run_file needs Stream authority (granted: {machine.level}); ask with"
                " request_authority(level='stream', reason=...)"
            )
        if not machine.homed:
            problems.append("the machine hasn't been homed since UGS connected")
        if machine.state not in ("Idle",):
            problems.append(f"the controller is {machine.state}, not Idle")
        if machine.stream not in ("idle", "completed", "error"):
            problems.append(f"a stream is already {machine.stream}")
        if problems:
            raise ToolError(
                "Refused, nothing was loaded:\n" + "\n".join(f"- {x}" for x in problems)
            )
        prof, _ = await profile()
        wco = list(machine.wco) if machine.wco else None
        analysis = await anyio.to_thread.run_sync(lambda: analyze_path(p, profile=prof, wco=wco))
        info = await _guard(bridge.file_load(str(p)))
        await _guard(bridge.file_run())
        warn = [w for w in analysis.get("warnings") or [] if w.get("level") in ("error", "warn")]
        out = [
            f"Loaded {p.name} ({info.get('lines')} lines, sha256 {str(info.get('sha256'))[:12]}…).",
            "Waiting for the operator to confirm the run in UGS; nothing runs before that.",
            analysis.get("summary", "").splitlines()[0] if analysis.get("summary") else "",
        ]
        if warn:
            out.append(f"{len(warn)} warning(s), first ones:")
            out.extend(f"  - {w['level'].upper()}: {w['message']}" for w in warn[:5])
        return "\n".join(x for x in out if x)

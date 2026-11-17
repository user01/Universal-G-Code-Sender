"""Turn bridge JSON into plain words for Claude: status, settings, log records."""

from __future__ import annotations

import json
import re
from collections.abc import Sequence
from typing import Any

from shapeoko_surface.controller.grbl_settings import SETTING_NAMES

from ugs_mcp.safety import STALE_REPORT_S

ALARMS: dict[int, str] = {
    1: "hard limit triggered; position is lost, re-home",
    2: "soft limit: the target is outside the machine travel",
    3: "reset while moving; position may be lost, re-home",
    4: "probe was already triggered before the probe move started",
    5: "probe didn't make contact within the search distance",
    6: "homing failed: reset during the homing cycle",
    7: "homing failed: safety door opened during homing",
    8: "homing failed: couldn't clear the limit switch on pull-off",
    9: "homing failed: limit switch not found within the search distance",
    10: "homing failed: second dual-axis limit switch not found",
}

ERRORS: dict[int, str] = {
    1: "expected a letter (G-code words are a letter and a value)",
    2: "bad or missing number",
    3: "'$' command not recognized",
    4: "negative value",
    5: "homing is disabled ($22)",
    8: "'$' command only valid when idle",
    9: "G-code locked out during alarm or jog",
    10: "soft limits need homing enabled",
    15: "jog target exceeds machine travel",
    16: "jog command has no '=' or contains prohibited G-code",
    20: "unsupported or invalid G-code command (M6 without a tool-change handler gives this)",
    22: "feed rate not set",
    24: "two commands that both use axis words",
    25: "a word is repeated in the block",
    33: "invalid arc target or radius",
}

PIN_NAMES = {
    "P": "probe input active",
    "X": "X limit switch active",
    "Y": "Y limit switch active",
    "Z": "Z limit switch active",
    "D": "door open",
    "H": "feed-hold button pressed",
    "R": "reset button pressed",
    "S": "cycle-start button pressed",
}
_PIN_KEYS = {
    "probe": "P",
    "x_limit": "X",
    "y_limit": "Y",
    "z_limit": "Z",
    "door": "D",
    "hold": "H",
    "reset": "R",
    "start": "S",
}


def setting_name_units(number: int) -> tuple[str, str]:
    """(name, units) for a GRBL ``$n`` setting, from resurfacer's table."""
    full = SETTING_NAMES.get(number)
    if full is None:
        return (f"${number}", "")
    m = re.match(r"^(.*?)\s*\(([^)]*)\)\s*$", full)
    if m:
        return m.group(1), m.group(2)
    if "mask" in full:
        return full, "mask"
    if full.startswith(("Invert", "Report", "Soft", "Hard", "Homing cycle", "Laser")):
        return full, "boolean"
    return full, ""


def _xyz(v: Sequence[float] | None) -> str:
    if not v:
        return "unknown"
    return "  ".join(f"{a} {x:.3f}" for a, x in zip("XYZ", v, strict=False))


def pins_words(pins: dict[str, Any] | None) -> str:
    if not pins:
        return "no inputs active"
    letters: set[str] = set(str(pins.get("raw") or ""))
    for key, letter in _PIN_KEYS.items():
        if pins.get(key):
            letters.add(letter)
    words = [PIN_NAMES.get(ch, f"pin {ch} active") for ch in "PXYZDHRS" if ch in letters]
    return ", ".join(words) if words else "no inputs active"


def alarm_words(code: int | None, text: str | None = None) -> str:
    if code is None:
        return text or "alarm"
    return f"ALARM:{code}: {text or ALARMS.get(code, 'unknown alarm')}"


def report_age(s: dict[str, Any]) -> float | None:
    """Seconds since the last controller status report, or None if the bridge doesn't say."""
    rep = s.get("controller_report")
    if not isinstance(rep, dict) or rep.get("age_s") is None:
        return None
    return float(rep["age_s"])


def silent_text(age: float) -> str:
    return (
        f"NO STATUS FROM CONTROLLER for {age:.0f} s: it may be frozen or disconnected (UGS still"
        " shows it connected). The state and positions below are from the last report, not now."
        " Ask the operator to check the controller and USB, then disconnect and reconnect in UGS."
    )


def status_text(s: dict[str, Any]) -> str:
    out: list[str] = []
    conn = s.get("connection") or {}
    if conn.get("connected"):
        fw = f", firmware {conn['firmware']}" if conn.get("firmware") else ""
        out.append(f"Connected to {conn.get('port') or '?'} ({conn.get('driver') or '?'}{fw}).")
        age = report_age(s)
        if age is not None and age >= STALE_REPORT_S:
            out.append(silent_text(age))
    else:
        out.append("UGS is not connected to the controller.")
    c = s.get("controller")
    if c:
        state = c.get("state", "Unknown")
        sub = c.get("substate")
        out.append(f"State: {state}" + (f":{sub}" if sub is not None else "") + ".")
        out.append(f"Homed this connection: {'yes' if s.get('homed_this_connection') else 'NO'}.")
        out.append(f"Machine position (MPos): {_xyz(c.get('mpos'))}")
        out.append(f"Work position (WPos): {_xyz(c.get('wpos'))}")
        out.append(f"Work offset (WCO): {_xyz(c.get('wco'))}")
        feed, spindle = c.get("feed"), c.get("spindle")
        acc = c.get("accessories") or {}
        sp_on = acc.get("spindle_cw") or acc.get("spindle_ccw")
        sp = "on" if sp_on else "off"
        if spindle:
            sp += f" (S{spindle:g})"
        out.append(f"Feed {feed or 0:g} mm/min, spindle {sp}.")
        out.append(f"Inputs: {pins_words(c.get('pins'))}.")
        if c.get("line") is not None:
            out.append(f"Controller line number: {c['line']}.")
        buf = c.get("buffer")
        if buf:
            out.append(
                f"Buffers free: planner {buf.get('planner', '?')}, serial {buf.get('rx', '?')}."
            )
    elif conn.get("connected"):
        out.append("No status report yet.")
    alarm = s.get("alarm")
    if alarm:
        out.append(f"Alarm: {alarm_words(alarm.get('code'), alarm.get('text'))}")
    auth = s.get("authority") or {}
    if auth.get("locked"):
        out.append("Claude access: LOCKED by the operator.")
    else:
        lvl = auth.get("level", "observe")
        exp = auth.get("motion_expires_at")
        out.append(
            f"Claude access: unlocked, level {lvl}" + (f" (expires {exp})" if exp else "") + "."
        )
    st = s.get("stream") or {}
    state = st.get("state", "idle")
    if state == "idle":
        out.append("Stream: idle.")
    else:
        prog = ""
        if st.get("lines_total"):
            prog = f", line {st.get('line_sent') or 0}/{st['lines_total']} sent"
            if st.get("line_acked") is not None:
                prog += f", {st['line_acked']} acknowledged"
        out.append(f"Stream: {state} {st.get('file') or ''}{prog}.".replace(" .", "."))
    tool = s.get("tool")
    if tool and tool.get("current"):
        known = "length measured" if tool.get("length_known") else "length NOT measured"
        out.append(f"Tool: {tool['current']} ({known}).")
    icp = s.get("interceptor")
    if icp:
        out.append(
            f"Tool-change interceptor: {icp.get('state', '?')}"
            + (f", step {icp['step']}" if icp.get("step") else "")
            + (f", tool {icp['tool']}" if icp.get("tool") else "")
            + "."
        )
    out.append(f"(log seq {s.get('seq', '?')})")
    return "\n".join(out)


def record_line(r: dict[str, Any]) -> str:
    t = str(r.get("t", ""))
    clock = t[11:23] if len(t) >= 23 else t
    kind = r.get("kind")
    d = r.get("data") or {}
    src = r.get("src", "?")
    if kind == "tx":
        body = f"> {d.get('line', '')}"
        if d.get("realtime"):
            body += f" [{d['realtime']}]"
    elif kind == "rx":
        body = f"< {d.get('line', '')}"
        line = str(d.get("line", ""))
        if m := re.match(r"^error:(\d+)", line):
            body += f"  ({ERRORS.get(int(m.group(1)), 'unknown error')})"
        elif m := re.match(r"^ALARM:(\d+)", line):
            body += f"  ({ALARMS.get(int(m.group(1)), 'unknown alarm')})"
    elif kind == "message":
        body = f"[{d.get('type', 'INFO')}] {d.get('text', '')}"
    elif kind == "command":
        body = f"command {d.get('id')} '{d.get('line', '')}' {d.get('state')}"
        if d.get("response"):
            body += " -> " + " | ".join(d["response"])
    elif kind == "status":
        body = f"status {d.get('raw') or d.get('state')}"
    else:
        body = f"{kind} " + json.dumps(d, separators=(",", ":"), ensure_ascii=False)
    return f"#{r.get('seq')} {clock} {src:<10} {body}"


def settings_text(
    data: dict[str, Any], profile: dict[str, Any] | None, profile_source: str | None
) -> str:
    settings: dict[str, Any] = data.get("settings") or {}
    if not settings:
        return (
            "No $$ output has been seen on this connection yet. Run query('$$') to read the"
            " settings."
        )
    expected: dict[str, Any] = (profile or {}).get("expected_settings") or {}
    rows: list[str] = []
    mismatches: list[str] = []

    def key_num(k: str) -> int:
        try:
            return int(k.lstrip("$"))
        except ValueError:
            return 10**6

    for key in sorted(settings, key=key_num):
        entry = settings[key]
        value = entry.get("value") if isinstance(entry, dict) else entry
        num = key_num(key)
        name, units = setting_name_units(num)
        if isinstance(entry, dict):
            name = entry.get("name") or name
            units = entry.get("units") or units
        mark = ""
        if key in expected:
            try:
                ok = abs(float(str(value)) - float(expected[key])) < 1e-6
            except (TypeError, ValueError):
                ok = False
            if not ok:
                mark = f"  <-- MISMATCH: profile expects {expected[key]:g}"
                mismatches.append(f"{key} is {value} but the profile expects {expected[key]:g}")
        unit_txt = f" {units}" if units and units not in ("mask", "boolean") else ""
        rows.append(f"{key:>5} = {value}{unit_txt:<10} {name}{mark}")
    head = [f"GRBL settings (read {data.get('read_at') or 'at an unknown time'}):"]
    if mismatches:
        src = f" ({profile_source})" if profile_source else ""
        head.append(
            f"{len(mismatches)} setting(s) disagree with the machine profile{src}: "
            + "; ".join(mismatches)
            + ". The profile values win for envelope checks."
        )
    elif expected:
        head.append("All settings the profile checks match.")
    else:
        head.append("No machine profile settings to compare against.")
    missing = [k for k in expected if k not in settings]
    if missing:
        head.append("Not in $$ output: " + ", ".join(missing))
    return "\n".join(head + rows)


def duration(seconds: float) -> str:
    """45 s, 3 min, 2 h 05 min (the notifier's wording)."""
    s = round(seconds)
    if s < 120:
        return f"{s} s"
    minutes = round(seconds / 60)
    if minutes < 100:
        return f"{minutes} min"
    return f"{minutes // 60} h {minutes % 60:02d} min"


def _min(seconds: Any) -> float:
    return round(float(seconds or 0) / 60, 1)


def planner_time(est: dict[str, Any]) -> dict[str, Any]:
    """The bridge's GRBL planner estimate (``GET /file/estimate``) for analyze_file's ``time``."""
    return {
        "machine_min": _min(est.get("machine_s")),
        "tool_change_auto_min": _min(est.get("bitsetter_s")),
        "total_min": _min(est.get("total_s")),
        "operator_time": "not included (tool changes wait for the operator)",
        "naive_min": _min(est.get("naive_s")),
        "settings_from": est.get("settings_from"),
        "tools": [
            {"tool": t.get("tool"), "name": t.get("name"), "min": _min(t.get("s"))}
            for t in est.get("tools") or []
            if t.get("tool")
        ],
        "ops": [
            {
                "name": o.get("name"),
                "tool": o.get("tool"),
                "lines": [o.get("first_line"), o.get("last_line")],
                "start_min": _min(o.get("start_s")),
                "min": round((float(o.get("end_s") or 0) - float(o.get("start_s") or 0)) / 60, 1),
            }
            for o in est.get("ops") or []
        ],
        "tool_changes": [
            {"line": c.get("line"), "to": c.get("to"), "at_min": _min(c.get("at_s"))}
            for c in est.get("tool_changes") or []
        ],
        "assumptions": est.get("assumptions") or [],
        "method": "GRBL planner simulation in the bridge (accelerations, cornering, look-ahead)",
    }


def planner_summary(est: dict[str, Any]) -> str:
    """One line for analyze_file's summary."""
    changes = len(est.get("tool_changes") or [])
    text = (
        "Planner estimate (bridge, GRBL accelerations, settings from the"
        f" {est.get('settings_from')}): {duration(float(est.get('machine_s') or 0))} of"
        " machine time"
    )
    if changes:
        text += (
            f" + {changes} tool change{'s' if changes != 1 else ''}"
            f" (≈ {duration(float(est.get('bitsetter_s') or 0))} automatic, plus the"
            " operator's time)"
        )
    tools = [t for t in est.get("tools") or [] if t.get("tool")]
    if tools:
        text += "; per tool: " + ", ".join(
            f"{t['tool']} {duration(float(t.get('s') or 0))}" for t in tools
        )
    return text + "."


def clock(iso: str | None) -> str | None:
    """'18:42' from an ISO date-time."""
    if not iso:
        return None
    m = re.search(r"T(\d\d:\d\d)", iso)
    return m.group(1) if m else None


def eta_lines(eta: dict[str, Any] | None) -> list[str]:
    """``/job/state``'s ``eta`` in plain words."""
    if not eta:
        return []
    elapsed = duration(float(eta.get("elapsed_s") or 0))
    if not eta.get("active"):
        last = f"Last job {eta.get('file') or ''}: {eta.get('result') or 'ended'} after {elapsed}"
        if eta.get("estimate_machine_s"):
            last += (
                f" (machine time {duration(float(eta.get('machine_elapsed_s') or 0))},"
                f" estimated {duration(float(eta['estimate_machine_s']))})"
            )
        return [last + "."]
    if eta.get("adjusted_remaining_s") is None:
        return [f"Running {elapsed}; no time estimate yet."]
    what = " ".join(str(x) for x in (eta.get("current_tool"), eta.get("current_op")) if x)
    head = f"{what}: " if what else ""
    if eta.get("op_remaining_s") is not None:
        head += f"{duration(float(eta['op_remaining_s']))} left in this operation; "
    line = f"{head}job ≈ {duration(float(eta['adjusted_remaining_s']))} left"
    if c := clock(eta.get("predicted_finish")):
        line += f", done ≈ {c}"
    n = int(eta.get("tool_changes_remaining") or 0)
    if n:
        line += f" (+{n} tool change{'s' if n != 1 else ''} waiting on the operator)"
    out = [line + "."]
    frac = eta.get("fraction")
    detail = f"{round(float(frac) * 100)} % of the machine time done; " if frac is not None else ""
    detail += (
        f"running {elapsed}, paused {duration(float(eta.get('paused_s') or 0))},"
        f" speed vs estimate x{float(eta.get('ratio') or 1):.2f}."
    )
    out.append(detail)
    if eta.get("waiting_for_tool_change"):
        out.append("UGS is waiting for the operator to change the tool.")
    nxt = eta.get("next_tool_change")
    if nxt and nxt.get("in_s") is not None:
        out.append(
            f"Next tool change: {nxt.get('tool')} at line {nxt.get('line')}"
            f" in ≈ {duration(float(nxt['in_s']))}."
        )
    return out

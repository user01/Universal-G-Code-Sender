"""``analyze_file``: extents, tools, operations, feeds, unsupported commands, time and
warnings against the machine envelope, the stock and the spoilboard (SPEC §5.1, §11.7).
"""

from __future__ import annotations

import bisect
import hashlib
from collections import Counter
from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Any

from ugs_mcp.gcode.headers import Headers, parse_headers, tool_id_from_number
from ugs_mcp.gcode.interpreter import Issue, Move, Program, interpret
from ugs_mcp.gcode.job import build_job, load_sidecar
from ugs_mcp.gcode.lexer import lex

DEFAULT_RAPID_MM_MIN = 5000.0
MAX_LINES_LISTED = 10

Box = dict[str, list[float]]


class _Extent:
    def __init__(self) -> None:
        self.lo = [float("inf")] * 3
        self.hi = [float("-inf")] * 3

    def add(
        self, lo: Sequence[float | None], hi: Sequence[float | None], grow: float = 0.0
    ) -> None:
        for i in range(3):
            g = grow if i < 2 else 0.0
            a, b = lo[i], hi[i]
            if a is not None:
                self.lo[i] = min(self.lo[i], a - g)
            if b is not None:
                self.hi[i] = max(self.hi[i], b + g)

    def box(self) -> Box | None:
        out: Box = {}
        for i, axis in enumerate("xyz"):
            if self.lo[i] <= self.hi[i]:
                out[axis] = [round(self.lo[i], 3), round(self.hi[i], 3)]
        return out or None


def _lines(items: Iterable[int]) -> list[int]:
    out = sorted(set(items))
    return out[:MAX_LINES_LISTED]


def _fmt_range(box: Box | None, axis: str) -> str:
    if not box or axis not in box:
        return "?"
    a, b = box[axis]
    return f"{a:g}..{b:g}"


def load_profile_envelope(
    profile: dict[str, Any] | None,
) -> tuple[list[float], list[float], bool] | None:
    if not profile:
        return None
    env = profile.get("envelope_mm")
    if not isinstance(env, dict):
        return None
    try:
        lo = [float(v) for v in env["min"]]
        hi = [float(v) for v in env["max"]]
    except (KeyError, TypeError, ValueError):
        return None
    return lo, hi, bool(env.get("verified", False))


def analyze_path(
    path: Path,
    *,
    profile: dict[str, Any] | None = None,
    wco: Sequence[float] | None = None,
) -> dict[str, Any]:
    data = path.read_bytes()
    text = data.decode("utf-8", errors="replace")
    sidecar, sidecar_path, sidecar_problems = load_sidecar(path)
    result = analyze_text(
        text,
        name=path.name,
        profile=profile,
        sidecar=sidecar,
        wco=wco,
    )
    result["file"] = {
        "path": str(path),
        "sha256": hashlib.sha256(data).hexdigest(),
        "bytes": len(data),
        "lines": result["file"]["lines"],
        "sidecar": str(sidecar_path) if sidecar_path else None,
    }
    for p in sidecar_problems:
        result["warnings"].insert(0, {"level": "warn", "code": "sidecar", "message": p})
    return result


def analyze_text(
    text: str,
    *,
    name: str = "<text>",
    profile: dict[str, Any] | None = None,
    sidecar: dict[str, Any] | None = None,
    wco: Sequence[float] | None = None,
) -> dict[str, Any]:
    lines = lex(text)
    prog = interpret(lines)
    headers = parse_headers(lines)
    job, source = build_job(prog, headers, sidecar)
    warnings: list[dict[str, Any]] = []

    def warn(level: str, code: str, message: str, line_numbers: Iterable[int] = ()) -> None:
        w: dict[str, Any] = {"level": level, "code": code, "message": message}
        ln = _lines(line_numbers)
        if ln:
            w["lines"] = ln
        warnings.append(w)

    tools_by_id = {t["id"]: t for t in job.get("tools", []) if isinstance(t, dict) and "id" in t}

    def radius(m: Move) -> float:
        if m.tool is None:
            return 0.0
        t = tools_by_id.get(tool_id_from_number(m.tool))
        d = t.get("diameter") if t else None
        return float(d) / 2 if isinstance(d, int | float) else 0.0

    cut, cut_tool, rapid, everything = _Extent(), _Extent(), _Extent(), _Extent()
    rapid_rate = _rapid_rate(headers, profile)
    t_cut = t_rapid = 0.0
    d_cut = d_rapid = 0.0
    feeds: Counter[float] = Counter()
    plunge_feeds: Counter[float] = Counter()
    unknown_radius: set[int] = set()
    spindle_off_cuts: list[int] = []
    for m in prog.moves:
        everything.add(m.lo, m.hi)
        if m.kind == "rapid":
            rapid.add(m.lo, m.hi)
            t_rapid += m.length / rapid_rate * 60
            d_rapid += m.length
            continue
        if m.kind == "probe":
            continue
        cut.add(m.lo, m.hi)
        r = radius(m)
        if r == 0.0 and m.tool is not None:
            unknown_radius.add(m.tool)
        cut_tool.add(m.lo, m.hi, grow=r)
        d_cut += m.length
        if m.inverse_time and m.feed:
            t_cut += 60.0 / m.feed
        elif m.feed:
            t_cut += m.length / m.feed * 60
            feeds[round(m.feed, 1)] += 1
            if m.is_plunge:
                plunge_feeds[round(m.feed, 1)] += 1
        if not m.spindle_on and m.hi[2] is not None and m.hi[2] < 0:
            spindle_off_cuts.append(m.line)
    dwell_s = sum(p for _, p in prog.dwells)
    total_s = t_cut + t_rapid + dwell_s

    cut_box = cut.box()
    cut_tool_box = cut_tool.box()
    all_box = everything.box()

    # Operations: add per-op extents and time.
    move_lines = [m.line for m in prog.moves]
    ops_out: list[dict[str, Any]] = []
    for op in job.get("operations", []):
        a, b = op["lines"]
        i = bisect.bisect_left(move_lines, a)
        j = bisect.bisect_right(move_lines, b)
        e = _Extent()
        secs = 0.0
        op_feeds: Counter[float] = Counter()
        for m in prog.moves[i:j]:
            if m.kind == "rapid":
                secs += m.length / rapid_rate * 60
                continue
            if m.is_cut:
                e.add(m.lo, m.hi, grow=radius(m))
                if m.feed:
                    secs += m.length / m.feed * 60
                    op_feeds[round(m.feed, 1)] += 1
        entry = {
            k: v
            for k, v in op.items()
            if k
            in (
                "number",
                "name",
                "tool",
                "lines",
                "z_range",
                "plunge_feed",
                "description",
                "tool_in_gcode",
            )
        }
        entry.setdefault("number", len(ops_out) + 1)
        entry["est_min"] = round(secs / 60, 1)
        if op_feeds:
            entry["feeds"] = [f for f, _ in op_feeds.most_common(3)]
        if (box := e.box()) is not None:
            entry["extent_incl_cutter"] = box
        ops_out.append(entry)
        if "tool_in_gcode" in op:
            warn(
                "warn",
                "op-tool",
                f"operation {op.get('number')} '{op['name']}' is labelled {op['tool']} but the"
                f" G-code has {op['tool_in_gcode']} loaded there",
                [a],
            )

    # Unsupported commands.
    unsupported = []
    reasons = {iss.code: iss.message for iss in prog.issues if iss.code in prog.unsupported}
    for code, where in sorted(prog.unsupported.items()):
        unsupported.append(
            {
                "code": code,
                "count": len(where),
                "lines": _lines(where),
                "reason": reasons.get(code, ""),
            }
        )

    # Issues from the interpreter, grouped by code so a 1000-line pattern is one warning.
    _group_issues(prog.issues, prog.unsupported, warn)

    # Tool changes.
    tool_changes = [
        {
            "line": tc.line,
            "tool": None if tc.tool is None else tool_id_from_number(tc.tool),
            "has_t": tc.has_t,
        }
        for tc in prog.tool_changes
    ]
    for tc in prog.tool_changes:
        tid = None if tc.tool is None else tool_id_from_number(tc.tool)
        if tid and tools_by_id and tid not in tools_by_id:
            warn(
                "info",
                "tool-unknown",
                f"M6 loads {tid}, which the job's tool list doesn't describe",
                [tc.line],
            )
    if unknown_radius:
        names = ", ".join(tool_id_from_number(t) for t in sorted(unknown_radius))
        warn(
            "info",
            "tool-diameter",
            f"no diameter known for {names}: the 'incl cutter' extents leave it out",
        )
    if spindle_off_cuts:
        warn(
            "warn",
            "spindle-off",
            f"{len(spindle_off_cuts)} cutting move(s) below Z0 with the spindle off"
            " (no M3 before them)",
            spindle_off_cuts,
        )

    spindle = _spindle_checks(prog, job, profile, warn)
    _clearance_checks(prog, job, warn)
    _stock_checks(job, cut_box, cut_tool_box, warn)
    _envelope_checks(prog, profile, all_box, cut_tool_box, wco, warn)
    _header_checks(
        headers, job, cut_box, cut_tool_box, total_s, d_cut, d_rapid, len(prog.tool_changes), warn
    )

    if prog.final_distance == "G91":
        warn("warn", "G91-at-end", "the file ends in incremental mode (G91)")
    if prog.final_spindle_on:
        warn("warn", "spindle-at-end", "the file ends with the spindle still on")
    if prog.program_end is None:
        warn("info", "no-M2", "no program end (M2/M30)")
    elif prog.program_end < len(lines) and any(ln.code for ln in lines[prog.program_end :]):
        warn(
            "info", "after-M2", f"there is G-code after the program end on line {prog.program_end}"
        )

    time = {
        "total_min": round(total_s / 60, 1),
        "cut_min": round(t_cut / 60, 1),
        "rapid_min": round(t_rapid / 60, 1),
        "dwell_s": round(dwell_s, 1),
        "cut_m": round(d_cut / 1000, 2),
        "rapid_m": round(d_rapid / 1000, 2),
        "rapid_rate_mm_min": rapid_rate,
        "tool_changes": len(prog.tool_changes),
        "method": "distance / programmed feed, no acceleration; tool changes not included",
    }
    if headers.est_runtime:
        time["header_min"] = headers.est_runtime.get("minutes")

    result: dict[str, Any] = {
        "file": {"name": name, "lines": len(lines)},
        "job_source": source,
        "job": job,
        "units": job.get("units", "mm"),
        "tool_changes": tool_changes,
        "operations": ops_out,
        "extents": {
            "cut": cut_box,
            "cut_incl_cutter": cut_tool_box,
            "rapid": rapid.box(),
            "all_moves": all_box,
            "machine_moves_G53": [
                {"line": mm.line, "target": list(mm.target)}
                for mm in prog.machine_moves[:MAX_LINES_LISTED]
            ],
            "note": "work coordinates in mm; incl_cutter grows X/Y by the tool radius",
        },
        "feeds": {
            "cut": [f for f, _ in feeds.most_common(8)],
            "plunge": [f for f, _ in plunge_feeds.most_common(4)],
        },
        "time": time,
        "spindle": spindle,
        "unsupported": unsupported,
        "warnings": warnings,
    }
    result["summary"] = summarize(result)
    return result


def _spindle_checks(
    prog: Program, job: dict[str, Any], profile: dict[str, Any] | None, warn: Any
) -> dict[str, Any]:
    """BitRunner (ledger C7): the relay only switches power, the router's dial sets the speed.
    Too low an S leaves the router off although GRBL (and so UGS) reports the spindle on. The
    profile knows one S that switched it on and one that didn't; S at or below the "off" value
    is an error, S between the two is a warning (not verified). Speed comes from the header's
    ROUTER DIAL, not from S."""
    sp = (profile or {}).get("spindle") or {}
    control = sp.get("control")
    out: dict[str, Any] = {
        "control": control,
        "s_values": sorted({s for _, s in prog.spindle_speeds if s is not None}),
    }
    dials = {
        t["id"]: t["router_dial"]
        for t in job.get("tools", []) or []
        if isinstance(t, dict) and t.get("router_dial") is not None and "id" in t
    }
    if control == "bitrunner":
        out["speed"] = "router dial (the BitRunner only switches the router on and off)"
        if dials:
            out["router_dial"] = dials
        on = sp.get("s_on_verified")
        off = sp.get("s_off_verified")
        on = float(on) if isinstance(on, int | float) and on > 0 else None
        off = float(off) if isinstance(off, int | float) and off >= 0 else None
        if on is not None:
            out["s_on_verified"] = on
        if off is not None:
            out["s_off_verified"] = off
        dead = [n for n, s in prog.spindle_speeds if s is None or (off is not None and s <= off)]
        unsure = [
            n
            for n, s in prog.spindle_speeds
            if s is not None and (off is None or s > off) and on is not None and s < on
        ]
        if dead:
            warn(
                "error",
                "bitrunner-s-low",
                f"spindle on with no S or S{off:g} or lower on {len(dead)} line(s): the"
                " BitRunner will not switch the router on, although UGS will show the spindle on."
                f" Use S{on or 18000:g} or more; the router's dial sets the speed"
                if off is not None
                else f"spindle on with no S on {len(dead)} line(s): the BitRunner needs an S"
                " value to switch the router on",
                dead,
            )
        if unsure:
            values = sorted({s for n, s in prog.spindle_speeds if n in set(unsure) and s})
            warn(
                "warn",
                "bitrunner-s-unverified",
                f"S{'/'.join(f'{v:g}' for v in values)} is between S{off or 0:g} (BitRunner"
                f" stays off) and S{on:g} (switches on): not verified on this machine (ledger"
                " C7). Watch that the router starts, or use S"
                f"{on:g}",
                unsure,
            )
    elif control == "pwm":
        out["speed"] = "S (PWM/VFD spindle)"
    return out


def _rapid_rate(headers: Headers, profile: dict[str, Any] | None) -> float:
    if headers.rapid_rate:
        return headers.rapid_rate
    if profile:
        for key in ("rapid_mm_min", "rapid_rate_mm_min"):
            v = profile.get(key)
            if isinstance(v, int | float) and v > 0:
                return float(v)
    return DEFAULT_RAPID_MM_MIN


def _group_issues(issues: list[Issue], unsupported: dict[str, list[int]], warn: Any) -> None:
    grouped: dict[tuple[str, str], list[Issue]] = {}
    for iss in issues:
        if iss.code in unsupported:
            continue
        grouped.setdefault((iss.code, iss.level), []).append(iss)
    for (code, level), group in grouped.items():
        first = group[0]
        msg = (
            first.message
            if len(group) == 1
            else f"{first.message} (and {len(group) - 1} more like it)"
        )
        warn(level, code, msg, (i.line for i in group))


def _clearance_checks(prog: Program, job: dict[str, Any], warn: Any) -> None:
    clearance = job.get("clearance_z_work")
    low_rapids: list[int] = []
    rapid_into_stock: list[int] = []
    for m in prog.moves:
        if m.kind != "rapid":
            continue
        sz, ez = m.start[2], m.end[2]
        if ez is not None and ez < -1e-6 and (sz is None or ez < sz - 1e-6):
            rapid_into_stock.append(m.line)
        if not isinstance(clearance, int | float) or sz is None or ez is None:
            continue
        moved_xy = any(
            a is not None and b is not None and abs(a - b) > 1e-6
            for a, b in zip(m.start[:2], m.end[:2], strict=True)
        )
        if moved_xy and max(sz, ez) < clearance - 0.01:
            low_rapids.append(m.line)
    if rapid_into_stock:
        warn(
            "warn",
            "rapid-below-zero",
            f"{len(rapid_into_stock)} rapid (G0) move(s) go down below Z0; safe only where earlier"
            " operations already cleared the material",
            rapid_into_stock,
        )
    if low_rapids:
        warn(
            "info",
            "rapid-below-clearance",
            f"{len(low_rapids)} rapid XY move(s) run below the clearance height Z{clearance:+g}",
            low_rapids,
        )


def _stock_checks(
    job: dict[str, Any], cut_box: Box | None, cut_tool_box: Box | None, warn: Any
) -> None:
    stock = job.get("stock")
    limit = job.get("spoilboard_limit_z")
    zmin = cut_box["z"][0] if cut_box and "z" in cut_box else None
    if isinstance(limit, int | float) and zmin is not None and zmin < limit - 1e-3:
        warn(
            "error",
            "below-spoilboard-limit",
            f"cuts reach Z{zmin:g}, below the job's spoilboard limit Z{limit:g}",
        )
    if not isinstance(stock, dict) or not isinstance(stock.get("size"), list):
        return
    if stock.get("origin") != "front-left-top":
        return
    w, d, h = (float(v) for v in stock["size"])
    if zmin is not None and zmin < -h - 1e-3:
        depth = -h - zmin
        if isinstance(limit, int | float) and zmin >= limit - 1e-3:
            warn(
                "info",
                "into-spoilboard",
                f"cuts go {depth:.2f} mm into the spoilboard"
                f" (stock {h:g} mm thick, deepest Z{zmin:g});"
                f" the job allows down to Z{limit:g}",
            )
        else:
            warn(
                "warn",
                "into-spoilboard",
                f"cuts go {depth:.2f} mm below the stock bottom into the spoilboard"
                f" (stock {h:g} mm thick, deepest Z{zmin:g})",
            )
    if cut_tool_box and "x" in cut_tool_box and "y" in cut_tool_box:
        (x0, x1), (y0, y1) = cut_tool_box["x"], cut_tool_box["y"]
        out = []
        if x0 < -1e-3:
            out.append(f"{-x0:.1f} mm past the left edge")
        if x1 > w + 1e-3:
            out.append(f"{x1 - w:.1f} mm past the right edge")
        if y0 < -1e-3:
            out.append(f"{-y0:.1f} mm past the front edge")
        if y1 > d + 1e-3:
            out.append(f"{y1 - d:.1f} mm past the back edge")
        if out:
            warn(
                "info",
                "outside-stock",
                f"the cutter goes outside the {w:g} x {d:g} mm stock: " + ", ".join(out),
            )


def _envelope_checks(
    prog: Program,
    profile: dict[str, Any] | None,
    all_box: Box | None,
    cut_tool_box: Box | None,
    wco: Sequence[float] | None,
    warn: Any,
) -> None:
    env = load_profile_envelope(profile)
    if env is None:
        warn("info", "no-profile", "no machine profile: envelope checks skipped")
        return
    lo, hi, verified = env
    note = "" if verified else " (profile envelope not verified yet)"
    for mm in prog.machine_moves:
        for i, axis in enumerate("XYZ"):
            v = mm.target[i]
            if v is not None and not (lo[i] - 1e-6 <= v <= hi[i] + 1e-6):
                warn(
                    "error",
                    "G53-outside-envelope",
                    f"line {mm.line}: G53 {axis}{v:g} is outside the machine envelope"
                    f" {axis} {lo[i]:g}..{hi[i]:g}{note}",
                    [mm.line],
                )
    box = all_box
    if box:
        for i, axis in enumerate("xyz"):
            if axis in box:
                span = box[axis][1] - box[axis][0]
                if span > hi[i] - lo[i] + 1e-6:
                    warn(
                        "error",
                        "larger-than-envelope",
                        f"the moves span {span:.1f} mm in {axis.upper()}, more than the machine's"
                        f" {hi[i] - lo[i]:g} mm{note}",
                    )
    if wco is None or box is None:
        return
    for i, axis in enumerate("xyz"):
        if axis not in box:
            continue
        a, b = box[axis][0] + wco[i], box[axis][1] + wco[i]
        if a < lo[i] - 1e-6 or b > hi[i] + 1e-6:
            warn(
                "error",
                "outside-envelope",
                f"with the current work offset the moves reach machine"
                f" {axis.upper()} {a:.1f}..{b:.1f},"
                f" outside the envelope {lo[i]:g}..{hi[i]:g}{note}",
            )
    _ = cut_tool_box


def _header_checks(
    headers: Headers,
    job: dict[str, Any],
    cut_box: Box | None,
    cut_tool_box: Box | None,
    total_s: float,
    d_cut: float,
    d_rapid: float,
    n_tool_changes: int,
    warn: Any,
) -> None:
    ext = headers.cut_extent
    box = cut_tool_box if ext and ext.get("includes_cutter") else cut_box
    if ext and box:
        diffs = []
        for axis in ("x", "y"):
            if axis in ext and axis in box:
                for k in (0, 1):
                    if abs(ext[axis][k] - box[axis][k]) > 0.5:
                        diffs.append(
                            f"{axis.upper()} header {ext[axis][k]:g} vs G-code {box[axis][k]:g}"
                        )
        if "z_min" in ext and "z" in box and abs(ext["z_min"] - box["z"][0]) > 0.05:
            diffs.append(f"Zmin header {ext['z_min']:g} vs G-code {box['z'][0]:g}")
        if diffs:
            warn(
                "warn",
                "header-extent",
                "the CUT EXTENT header disagrees with the G-code: " + "; ".join(diffs),
            )
    rt = headers.est_runtime
    if rt and rt.get("minutes"):
        mine = total_s / 60 + n_tool_changes * float(rt.get("tool_change_s", 0.0)) / 60
        if abs(mine - rt["minutes"]) > max(2.0, 0.2 * rt["minutes"]):
            warn(
                "info",
                "header-runtime",
                f"the header estimates {rt['minutes']:g} min, this analysis {mine:.1f} min",
            )
    if headers.op_list and headers.markers and len(headers.op_list) != len(headers.markers):
        warn(
            "warn",
            "header-ops",
            f"the OPERATIONS list names {len(headers.op_list)} operations but the file has"
            f" {len(headers.markers)} OP markers",
        )
    _ = (job, d_cut, d_rapid)


def summarize(r: dict[str, Any]) -> str:
    job = r["job"]
    t = r["time"]
    out = [f"{r['file']['name']}: {r['file']['lines']} lines, job data from {r['job_source']}."]
    if stock := job.get("stock"):
        size = " x ".join(f"{v:g}" for v in stock.get("size", []))
        mat = f" {stock['material']}" if stock.get("material") else ""
        out.append(f"Stock {size} mm{mat}.")
    if (z := job.get("zero")) and z.get("text"):
        out.append(f"Zero: {z['text']}")
    if (c := job.get("clearance_z_work")) is not None:
        out.append(f"Clearance Z{c:+g} (work).")
    tools = job.get("tools") or []
    if tools:
        out.append(
            "Tools: "
            + "; ".join(
                f"{tl['id']} {tl.get('name', tl.get('type', ''))} ({tl['diameter']:g} mm)"
                for tl in tools
            )
        )
    if r["tool_changes"]:
        seq = ", ".join(f"{tc['tool'] or '?'}@{tc['line']}" for tc in r["tool_changes"])
        out.append(f"Tool changes (M6, handled by the bridge's tool-change interceptor): {seq}.")
    ops = r["operations"]
    if ops:
        out.append(f"{len(ops)} operation(s):")
        for op in ops[:40]:
            zr = op.get("z_range")
            zt = f" Z {zr[0]:g}..{zr[1]:g}" if zr else ""
            out.append(
                f"  {op['number']:>2}. {op['name']} [{op.get('tool') or '?'}] lines"
                f" {op['lines'][0]}-{op['lines'][1]}{zt}, ~{op['est_min']:g} min"
            )
        if len(ops) > 40:
            out.append(f"  ... and {len(ops) - 40} more")
    ext = r["extents"]["cut_incl_cutter"]
    out.append(
        f"Cut extent incl. cutter: X {_fmt_range(ext, 'x')}  Y {_fmt_range(ext, 'y')}"
        f"  Z {_fmt_range(r['extents']['cut'], 'z')}"
    )
    sp = r.get("spindle") or {}
    if sp.get("control") == "bitrunner":
        dial = sp.get("router_dial") or {}
        dial_text = (
            ", ".join(f"{k} dial {v:g}" for k, v in dial.items()) if dial else "not in the header"
        )
        s_text = ", ".join(f"S{s:g}" for s in sp.get("s_values") or []) or "none"
        out.append(
            f"Router speed: set by the dial ({dial_text}); S only switches the BitRunner"
            f" ({s_text}"
            + (f"; S{sp['s_on_verified']:g} is known to work" if sp.get("s_on_verified") else "")
            + ")."
        )
    est = f"Estimated time: {t['total_min']:g} min (cut {t['cut_min']:g}, rapid {t['rapid_min']:g}"
    est += f", dwell {t['dwell_s']:g} s; no acceleration, tool changes not counted)"
    if t.get("header_min") is not None:
        est += f"; the header says {t['header_min']:g} min"
    out.append(est + ".")
    if r["unsupported"]:
        out.append(
            "Unsupported: "
            + "; ".join(
                f"{u['code']} x{u['count']} (first line {u['lines'][0]})" for u in r["unsupported"]
            )
        )
    ws = [w for w in r["warnings"] if w["level"] in ("error", "warn")]
    infos = [w for w in r["warnings"] if w["level"] == "info"]
    for w in ws:
        out.append(f"{w['level'].upper()}: {w['message']}")
    for w in infos:
        out.append(f"note: {w['message']}")
    if not ws:
        out.append("No errors or warnings.")
    return "\n".join(out)

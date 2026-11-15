"""Job model: the sidecar structure (``schema/job-sidecar.schema.json``), from a sidecar file,
the post-processor headers, or the G-code alone (in that order of preference).
"""

from __future__ import annotations

import bisect
import json
from collections import Counter
from pathlib import Path
from typing import Any

from ugs_mcp.gcode.headers import Headers, tool_id_from_number
from ugs_mcp.gcode.interpreter import Program
from ugs_mcp.gcode.lexer import Line

SIDECAR_FORMAT = "ugs-bridge-job/1"


def sidecar_candidates(path: Path) -> list[Path]:
    """``part.nc`` -> ``part.job.json``, then ``part.nc.job.json``."""
    return [path.with_suffix(".job.json"), path.with_name(path.name + ".job.json")]


def load_sidecar(path: Path) -> tuple[dict[str, Any] | None, Path | None, list[str]]:
    """Return (sidecar, its path, problems). Problems are human-readable and non-fatal."""
    for cand in sidecar_candidates(path):
        if not cand.is_file():
            continue
        try:
            data = json.loads(cand.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            return None, cand, [f"sidecar {cand.name} could not be read: {exc}"]
        problems = check_sidecar(data)
        if problems and not isinstance(data, dict):
            return None, cand, problems
        return data, cand, problems
    return None, None, []


def check_sidecar(data: Any) -> list[str]:
    """Light structural check (the full schema is in bridge/schema/job-sidecar.schema.json)."""
    if not isinstance(data, dict):
        return ["sidecar is not a JSON object"]
    out: list[str] = []
    if data.get("format") != SIDECAR_FORMAT:
        out.append(f"sidecar format should be {SIDECAR_FORMAT!r}, got {data.get('format')!r}")
    if data.get("units") not in ("mm", "in"):
        out.append("sidecar units must be 'mm' or 'in'")
    for i, op in enumerate(data.get("operations") or []):
        lines = op.get("lines") if isinstance(op, dict) else None
        if not (
            isinstance(lines, list) and len(lines) == 2 and all(isinstance(v, int) for v in lines)
        ):
            out.append(f"sidecar operation {i + 1} needs lines: [first, last]")
    for i, t in enumerate(data.get("tools") or []):
        if not (isinstance(t, dict) and "id" in t and isinstance(t.get("diameter"), int | float)):
            out.append(f"sidecar tool {i + 1} needs id and diameter")
    return out


def _trim_end(lines: list[Line], start: int, end: int) -> int:
    """Walk ``end`` back over blank and comment-only lines (never before ``start``)."""
    while end > start and not lines[end - 1].code:
        end -= 1
    return end


def operations(prog: Program, headers: Headers) -> tuple[list[dict[str, Any]], str]:
    """Operations with 1-based inclusive line ranges, and how they were found."""
    lines = prog.lines
    n_lines = len(lines)
    move_lines = sorted({m.line for m in prog.moves})
    m6_lines = [tc.line for tc in prog.tool_changes]

    def first_move_after(line: int) -> int | None:
        i = bisect.bisect_left(move_lines, line)
        return move_lines[i] if i < len(move_lines) else None

    def tool_at(line: int | None) -> str | None:
        if line is None:
            return None
        t = prog.tool_by_line[line]
        return None if t is None else tool_id_from_number(t)

    ops: list[dict[str, Any]] = []
    if headers.markers:
        by_number = {o["number"]: o for o in headers.op_list}
        markers = headers.markers
        for i, mk in enumerate(markers):
            first = first_move_after(mk.line)
            boundary = markers[i + 1].line if i + 1 < len(markers) else n_lines + 1
            if first is not None:
                later_m6 = [ln for ln in m6_lines if first < ln < boundary]
                if later_m6:
                    boundary = later_m6[0]
            end = _trim_end(lines, mk.line, boundary - 1)
            listed = by_number.get(mk.number, {})
            g_tool = tool_at(first)
            tool = mk.tool or listed.get("tool") or g_tool
            name = mk.name or listed.get("name") or mk.description or f"op{mk.number:02d}"
            op: dict[str, Any] = {
                "number": mk.number,
                "name": name,
                "tool": tool,
                "lines": [mk.line, end],
            }
            desc = mk.description or listed.get("description")
            if desc and desc != name:
                op["description"] = desc
            if g_tool and tool and g_tool != tool:
                op["tool_in_gcode"] = g_tool
            ops.append(op)
        return ops, "headers"

    if headers.freecad_ops:
        starts = headers.freecad_ops
        for i, (line, name) in enumerate(starts):
            nxt = starts[i + 1][0] if i + 1 < len(starts) else n_lines + 1
            first = first_move_after(line)
            if first is None or first >= nxt:
                continue
            end = _trim_end(lines, line, nxt - 1)
            ops.append(
                {"number": len(ops) + 1, "name": name, "tool": tool_at(first), "lines": [line, end]}
            )
        if ops:
            return ops, "freecad-comments"

    bounds = list(m6_lines)
    starts2 = [1, *bounds] if not bounds or bounds[0] != 1 else bounds
    for i, start in enumerate(starts2):
        nxt = starts2[i + 1] if i + 1 < len(starts2) else n_lines + 1
        first = first_move_after(start)
        if first is None or first >= nxt:
            continue
        end = _trim_end(lines, start, nxt - 1)
        tool = tool_at(first)
        name = f"{tool or 'no tool'}" if bounds else "whole file"
        ops.append({"number": len(ops) + 1, "name": name, "tool": tool, "lines": [start, end]})
    return ops, "tool-changes" if bounds else "whole-file"


def op_stats(prog: Program, ops: list[dict[str, Any]]) -> None:
    """Add z_range and plunge_feed (most common plunge feed) where missing, in place."""
    move_lines = [m.line for m in prog.moves]
    for op in ops:
        a, b = op["lines"]
        i = bisect.bisect_left(move_lines, a)
        j = bisect.bisect_right(move_lines, b)
        zs: list[float] = []
        plunges: Counter[float] = Counter()
        for m in prog.moves[i:j]:
            if not m.is_cut:
                continue
            for z in (m.lo[2], m.hi[2]):
                if z is not None:
                    zs.append(z)
            if m.is_plunge and m.feed:
                plunges[round(m.feed, 1)] += 1
        if zs:
            op.setdefault("z_range", [round(min(zs), 4), round(max(zs), 4)])
        if plunges:
            op.setdefault("plunge_feed", plunges.most_common(1)[0][0])


def build_job(
    prog: Program, headers: Headers, sidecar: dict[str, Any] | None
) -> tuple[dict[str, Any], str]:
    """The job in sidecar shape, and its source: ``sidecar``, ``headers`` or ``gcode``."""
    ops, op_source = operations(prog, headers)
    op_stats(prog, ops)
    units = headers.units or (
        "in" if "G20" in prog.units_seen and "G21" not in prog.units_seen else "mm"
    )
    job: dict[str, Any] = {"format": SIDECAR_FORMAT, "units": units}
    if headers.title:
        job["title"] = headers.title
    if headers.stock is not None:
        job["stock"] = headers.stock
    if headers.zero is not None:
        job["zero"] = headers.zero
    if headers.clearance_z_work is not None:
        job["clearance_z_work"] = headers.clearance_z_work
    if headers.tool_change_park_z_machine is not None:
        job["tool_change_park_z_machine"] = headers.tool_change_park_z_machine
    if headers.spoilboard_limit_z is not None:
        job["spoilboard_limit_z"] = headers.spoilboard_limit_z
    tools = [t for t in headers.tools if isinstance(t.get("diameter"), float | int)]
    if tools:
        job["tools"] = tools
    job["operations"] = ops
    job["operations_source"] = op_source
    if headers.cut_extent is not None:
        job["cut_extent"] = headers.cut_extent
    if headers.est_runtime is not None:
        job["est_runtime"] = headers.est_runtime
    if headers.rapid_rate is not None:
        job["rapid_rate"] = headers.rapid_rate
    source = "headers" if headers.found else "gcode"
    if sidecar:
        merged = dict(job)
        for k, v in sidecar.items():
            merged[k] = v
        if "operations" in sidecar:
            merged["operations_source"] = "sidecar"
            op_stats(prog, merged["operations"])
        return merged, "sidecar"
    return job, source

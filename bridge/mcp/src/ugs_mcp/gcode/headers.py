"""Parse post-processor header comments into the job-sidecar structure.

The grammar is documented in ``bridge/docs/post-headers.md``; keep the two in step (the Java
and JavaScript parsers implement the same document). Everything here is best effort: a file
from another post simply yields fewer fields.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from ugs_mcp.gcode.lexer import Line

NUM = r"[-+]?\d+(?:\.\d+)?"

_STOCK3 = re.compile(rf"({NUM})\s*x\s*({NUM})\s*x\s*({NUM})(?:\s*(mm|in)\b)?", re.I)
_STOCK2 = re.compile(rf"({NUM})\s*x\s*({NUM})(?:\s*(mm|in)\b)?", re.I)
_THICK_RANGE = re.compile(rf"raw\s+({NUM})\s*-\s*({NUM})\s*thick", re.I)
_THROUGH = re.compile(rf"through[- ]cut\s+Z\s*({NUM})", re.I)
_AXES_ASSIGN = re.compile(r"((?:[XYZ]0\s*)+)=\s*", re.I)
_WITH_TOOL = re.compile(r"\bwith\s+(T\d+)\b", re.I)
_Z_VALUE = re.compile(rf"\bZ\s*({NUM})", re.I)
_PARK_Z = re.compile(rf"park[^.]*?\bZ\s*({NUM})", re.I)

_KEYED: list[tuple[str, re.Pattern[str]]] = [
    ("file", re.compile(r"^FILE:\s*(.*)$")),
    ("project", re.compile(r"^PROJECT:\s*(.*)$")),
    ("stock", re.compile(r"^STOCK:\s*(.*)$")),
    ("zero", re.compile(r"^((?:WORK\s+)?ZERO\b[^:]*):\s*(.*)$")),
    ("units", re.compile(r"^UNITS:\s*(.*)$")),
    ("clearance", re.compile(r"^CLEARANCE:\s*(.*)$")),
    ("floor", re.compile(r"^FLOOR:\s*(.*)$")),
    ("tools_list", re.compile(r"^TOOLS\b[^:]*:\s*$")),
    ("tool", re.compile(r"^TOOL\b(?!S)(?!\s+CHANGE)[^:]*:\s*(T\d+\b.*)$")),
    ("operations_list", re.compile(r"^OPERATIONS\b[^:]*:\s*$")),
    ("cut_extent", re.compile(r"^(CUT EXTENT\b[^:]*):\s*(.*)$")),
    ("est_runtime", re.compile(r"^EST(?:IMATED)? RUNTIME:\s*(.*)$")),
]

_CONTINUED = frozenset({"zero", "clearance", "units", "cut_extent", "est_runtime", "floor"})
"""Keys whose indented continuation lines are appended to the value."""

_TOOL_ITEM = re.compile(r"^\s*\d+\.\s+(T\d+)\b\s*(.*)$")
_TOOL_ROW = re.compile(rf"^\s*(T\d+)\s+({NUM})\s*mm\b(.*)$")
_OP_ITEM = re.compile(r"^\s*(\d+)\.\s*\[(T\d+)\]\s*(?:~\s*min\s*(" + NUM + r")\s+)?(.*?)\s*$")
_OP_NAME_DESC = re.compile(r"^(\S+)\s+-\s+(.*)$")
_OP_MARKER = re.compile(r"^\s*-{2,}\s*OP\s+(\d+)\s*([:|])\s*(.*?)\s*-{2,}\s*$")
_RAPIDS = re.compile(rf"^\s*Rapids:\s*({NUM})\s*/\s*min", re.I)
_FREECAD_OP = re.compile(r"^\s*Begin operation:\s*(.+?)\s*$")
_EXTENT = re.compile(
    rf"X\s*({NUM})\s*\.\.\s*({NUM})\s+Y\s*({NUM})\s*\.\.\s*({NUM})"
    rf"(?:\s+Z\s*({NUM})\s*\.\.\s*({NUM}))?(?:\s+Zmin\s*({NUM}))?(?:\s+Zmax\s*({NUM}))?",
    re.I,
)
_RUNTIME_MIN = re.compile(rf"({NUM})\s*min\b", re.I)
_RUNTIME_CUT = re.compile(rf"\bcut\s+({NUM})\s*m\b", re.I)
_RUNTIME_RAPID = re.compile(rf"\brapid\s+({NUM})\s*m\b", re.I)
_RUNTIME_TC = re.compile(r"tool changes? count\s+(\d+)\s*s", re.I)

_DIA = re.compile(rf"\bdia\.?\s*({NUM})\s*mm\b", re.I)
_INCH_SIZE = re.compile(r"(?<![\w/.-])(\d+/\d+|\d+(?:\.\d+)?)\s*in?\b", re.I)
_MM_SIZE = re.compile(rf"(?<![\w/.-])({NUM})\s*mm\b", re.I)
_FEED = re.compile(rf"\bfeed\s+({NUM})(?:\s*/\s*plunge\s+({NUM}))?", re.I)
_PLUNGE = re.compile(rf"\bplunge\s+({NUM})", re.I)
_RPM = re.compile(rf"(?:\bRPM\s+({NUM})|({NUM})\s*RPM\b)", re.I)
_DIAL = re.compile(rf"\bROUTER DIAL\s+({NUM})", re.I)
_FLUTE_LEN = re.compile(rf"\bflute\s+({NUM})\s*mm\b", re.I)
_DOC = re.compile(rf"\bDOC\s+({NUM})", re.I)
_SEGMENTS = re.compile(r"\s{3,}")


def tool_id(text: str) -> str:
    """``T2`` / ``T02`` / ``t2`` -> ``T02``. Ids with three or more digits keep them."""
    return f"T{int(text.strip()[1:]):02d}"


def tool_id_from_number(n: int) -> str:
    return f"T{n:02d}"


@dataclass(slots=True)
class OpMarker:
    line: int
    number: int
    tool: str | None
    name: str | None
    description: str


@dataclass(slots=True)
class Headers:
    """Raw header facts, before line ranges are resolved against the G-code."""

    title: str | None = None
    stock: dict[str, Any] | None = None
    zero: dict[str, Any] | None = None
    units: str | None = None
    clearance_z_work: float | None = None
    tool_change_park_z_machine: float | None = None
    spoilboard_limit_z: float | None = None
    tools: list[dict[str, Any]] = field(default_factory=list)
    op_list: list[dict[str, Any]] = field(default_factory=list)
    markers: list[OpMarker] = field(default_factory=list)
    freecad_ops: list[tuple[int, str]] = field(default_factory=list)
    cut_extent: dict[str, Any] | None = None
    est_runtime: dict[str, Any] | None = None
    rapid_rate: float | None = None
    header_end: int = 0
    """Last line of the leading comment block."""
    raw: dict[str, str] = field(default_factory=dict)

    @property
    def found(self) -> bool:
        return bool(self.stock or self.tools or self.markers or self.cut_extent or self.op_list)


def parse_headers(lines: list[Line]) -> Headers:
    h = Headers()
    # Leading comment block: every line until the first one with code.
    block: list[tuple[int, str]] = []
    for line in lines:
        if line.code:
            break
        h.header_end = line.number
        for c in line.comments:
            block.append((line.number, c))
    _parse_block(h, block)

    for line in lines:
        for c in line.comments:
            if m := _OP_MARKER.match(c):
                h.markers.append(_marker(line.number, m))
            elif h.rapid_rate is None and (m2 := _RAPIDS.match(c)):
                h.rapid_rate = float(m2.group(1))
            elif m3 := _FREECAD_OP.match(c):
                h.freecad_ops.append((line.number, m3.group(1)))
    return h


def _marker(line: int, m: re.Match[str]) -> OpMarker:
    number = int(m.group(1))
    rest = m.group(3)
    if m.group(2) == "|":
        parts = [p.strip() for p in rest.split("|")]
        tool = tool_id(parts[0]) if parts and re.fullmatch(r"T\d+", parts[0]) else None
        name = parts[1] if len(parts) > 1 and parts[1] else None
        desc = " | ".join(parts[2:]) if len(parts) > 2 else ""
        return OpMarker(line, number, tool, name, desc)
    return OpMarker(line, number, None, None, rest.strip())


def _parse_block(h: Headers, block: list[tuple[int, str]]) -> None:
    context: str | None = None
    current: dict[str, Any] | None = None
    texts: dict[str, list[str]] = {}
    for _, comment in block:
        indented = comment[:1].isspace()
        stripped = comment.strip()
        if not stripped:
            continue
        if indented and context is not None:
            if context == "tools_list":
                if m := _TOOL_ITEM.match(comment) or _TOOL_ROW.match(comment):
                    current = {"id": tool_id(m.group(1)), "_lines": [comment.strip()]}
                    h.tools.append(current)
                elif current is not None:
                    current["_lines"].append(stripped)
            elif context == "operations_list" and (m := _OP_ITEM.match(comment)):
                h.op_list.append(_op_item(m))
            elif (
                context in _CONTINUED
                and context in texts
                and (context != "zero" or _AXES_ASSIGN.search(stripped))
            ):
                texts[context].append(stripped)
            continue
        matched = False
        for key, pattern in _KEYED:
            m = pattern.match(stripped)
            if not m:
                continue
            matched = True
            context = key
            current = None
            if key in ("tools_list", "operations_list"):
                break
            if key == "tool":
                tid = m.group(1).split()[0]
                current = {"id": tool_id(tid), "_lines": [m.group(1)]}
                h.tools.append(current)
                context = "tools_list"
                break
            value = m.group(m.lastindex or 1)
            if key == "zero":
                value = m.group(1) + ": " + m.group(2)
            if key == "cut_extent":
                value = m.group(1) + ": " + m.group(2)
            texts.setdefault(key, []).append(value)
            break
        if not matched:
            context = None
            current = None

    for key, parts in texts.items():
        h.raw[key] = " ".join(parts)
    if t := h.raw.get("project") or h.raw.get("file"):
        h.title = t
    if "stock" in h.raw:
        h.stock = parse_stock(h.raw["stock"])
        if (sb := _THROUGH.search(h.raw["stock"])) is not None:
            h.spoilboard_limit_z = float(sb.group(1))
    if "zero" in h.raw:
        h.zero = parse_zero(h.raw["zero"])
    if "units" in h.raw:
        u = h.raw["units"]
        h.units = "in" if "G20" in u else "mm" if "G21" in u else None
    if "clearance" in h.raw:
        c = h.raw["clearance"]
        if m := _Z_VALUE.search(c):
            h.clearance_z_work = float(m.group(1))
        if m := _PARK_Z.search(c):
            h.tool_change_park_z_machine = float(m.group(1))
    if "floor" in h.raw and (m := _Z_VALUE.search(h.raw["floor"])):
        h.spoilboard_limit_z = float(m.group(1))
    if "cut_extent" in h.raw:
        h.cut_extent = parse_extent(h.raw["cut_extent"])
    if "est_runtime" in h.raw:
        h.est_runtime = parse_runtime(h.raw["est_runtime"])
    merged: dict[str, dict[str, Any]] = {}
    for item in h.tools:
        tool = parse_tool(item["id"], item.pop("_lines"))
        if tool["id"] in merged:
            for k, v in tool.items():
                merged[tool["id"]].setdefault(k, v)
        else:
            merged[tool["id"]] = tool
    h.tools = list(merged.values())
    if h.stock is not None and h.zero is not None and _zero_is_stock_front_left_top(h.zero):
        h.stock["origin"] = "front-left-top"


def _op_item(m: re.Match[str]) -> dict[str, Any]:
    item: dict[str, Any] = {"number": int(m.group(1)), "tool": tool_id(m.group(2))}
    if m.group(3) is not None:
        item["start_min"] = float(m.group(3))
    rest = m.group(4)
    if nd := _OP_NAME_DESC.match(rest):
        item["name"] = nd.group(1)
        item["description"] = nd.group(2)
    else:
        item["description"] = rest
    return item


def parse_stock(text: str) -> dict[str, Any] | None:
    stock: dict[str, Any] = {"text": text}
    m3 = _STOCK3.search(text)
    if m3:
        size = [float(m3.group(i)) for i in (1, 2, 3)]
        unit = (m3.group(4) or "mm").lower()
        span = m3.span()
        after = m3.group(4) is not None
    else:
        m2 = _STOCK2.search(text)
        if not m2:
            return None
        size2 = [float(m2.group(1)), float(m2.group(2))]
        unit = (m2.group(3) or "mm").lower()
        rng = _THICK_RANGE.search(text)
        if rng is None:
            return None
        lo, hi = float(rng.group(1)), float(rng.group(2))
        size = [*size2, hi]
        stock["thickness_range"] = [lo, hi]
        span = m2.span()
        after = m2.group(3) is not None
    if unit == "in":
        size = [v * 25.4 for v in size]
    stock["size"] = size
    if re.search(r"at least|>=|≥|minimum", text[: span[0]], re.I):
        stock["minimum"] = True
    material: str | None = None
    if after:
        tail = re.split(r"[.,;\[(]", text[span[1] :], maxsplit=1)[0].strip()
        if tail:
            material = tail
    if material is None:
        head = re.sub(r"(?i)\bat least\b|>=|≥", "", text[: span[0]]).strip(" ,:-")
        if head:
            material = head
    if material:
        stock["material"] = material
    return stock


def parse_zero(text: str) -> dict[str, Any]:
    zero: dict[str, Any] = {"text": text}
    if m := _WITH_TOOL.search(text):
        zero["tool"] = tool_id(m.group(1))
    matches = list(_AXES_ASSIGN.finditer(text))
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        desc = text[m.end() : end]
        desc = re.split(r"\.\s|;|\s-\s", desc, maxsplit=1)[0].strip(" ,.;")
        for axis in re.findall(r"[XYZ]", m.group(1).upper()):
            zero[axis.lower()] = desc
    return zero


def _zero_is_stock_front_left_top(zero: dict[str, Any]) -> bool:
    stockish = r"stock|blank|piece|sheet|material|offcut"
    xy = f"{zero.get('x', '')} {zero.get('y', '')}"
    z = str(zero.get("z", ""))
    return bool(
        re.search(r"front[- ]left", xy, re.I)
        and re.search(stockish, xy, re.I)
        and re.search(r"\btop\b", z, re.I)
        and re.search(stockish, z, re.I)
    )


def parse_extent(text: str) -> dict[str, Any] | None:
    m = _EXTENT.search(text)
    if not m:
        return None
    out: dict[str, Any] = {
        "x": [float(m.group(1)), float(m.group(2))],
        "y": [float(m.group(3)), float(m.group(4))],
        "includes_cutter": bool(re.search(r"incl\w*\s+cutter", text, re.I)),
    }
    if m.group(5) is not None:
        out["z"] = [float(m.group(5)), float(m.group(6))]
        out["z_min"] = float(m.group(5))
    if m.group(7) is not None:
        out["z_min"] = float(m.group(7))
    if m.group(8) is not None:
        out["z_max"] = float(m.group(8))
    return out


def parse_runtime(text: str) -> dict[str, Any] | None:
    m = _RUNTIME_MIN.search(text)
    if not m:
        return None
    out: dict[str, Any] = {"minutes": float(m.group(1))}
    if c := _RUNTIME_CUT.search(text):
        out["cut_m"] = float(c.group(1))
    if r := _RUNTIME_RAPID.search(text):
        out["rapid_m"] = float(r.group(1))
    if t := _RUNTIME_TC.search(text):
        out["tool_change_s"] = float(t.group(1))
    return out


def _inch(text: str) -> float:
    if "/" in text:
        a, b = text.split("/")
        return float(a) / float(b) * 25.4
    return float(text) * 25.4


def tool_type(name: str) -> str:
    s = name.lower()
    if "ball" in s:
        return "ball"
    if re.search(r"v-?\s?(bit|groove|carve)|\bdeg\b|°|engrav", s):
        return "vbit"
    if "chamfer" in s:
        return "chamfer"
    if re.search(r"spiral|upcut|downcut|flat|end ?mill|o-flute|straight|compression|surfacing", s):
        return "flat"
    return "other"


def parse_tool(tid: str, lines: list[str]) -> dict[str, Any]:
    """``lines[0]`` is the item line (starting with the T id), the rest are continuations."""
    first = re.sub(r"^\s*(?:\d+\.\s+)?T\d+\b\s*", "", lines[0])
    segments = _SEGMENTS.split(first.strip())
    name = re.sub(r"\s{2,}", " ", segments[0]).strip(" ,") if segments else ""
    if len(lines) > 1:
        cont = _SEGMENTS.split(lines[1].strip())[0]
        if cont and not re.search(r"label|box|\bdia\b|feed|ROUTER|flute", cont, re.I):
            name = f"{name}, {cont}" if name else cont
    text = "   ".join([lines[0], *lines[1:]])
    tool: dict[str, Any] = {"id": tid}
    if name and not re.fullmatch(NUM + r"\s*mm.*", name):
        tool["name"] = name
    tool["type"] = tool_type(name)
    dia: float | None = None
    if (m := _DIA.search(text)) or (m := re.match(rf"^\s*T\d+\s+({NUM})\s*mm\b", lines[0])):
        dia = float(m.group(1))
    else:
        cleaned = re.sub(r"(?i)shank\s+\S+\s*in?\b", "", segments[0] if segments else "")
        if m := _INCH_SIZE.search(cleaned):
            dia = _inch(m.group(1))
        elif m := _MM_SIZE.search(cleaned):
            dia = float(m.group(1))
    if dia is not None:
        tool["diameter"] = round(dia, 4)
    if m := _FEED.search(text):
        tool["feed"] = float(m.group(1))
        if m.group(2):
            tool["plunge"] = float(m.group(2))
    if "plunge" not in tool and (m := _PLUNGE.search(text)):
        tool["plunge"] = float(m.group(1))
    if m := _RPM.search(text):
        tool["rpm"] = float(m.group(1) or m.group(2))
    if m := _DIAL.search(text):
        tool["router_dial"] = float(m.group(1))
    if m := _FLUTE_LEN.search(text):
        tool["flute_length"] = float(m.group(1))
    if m := _DOC.search(text):
        tool["doc"] = float(m.group(1))
    return tool

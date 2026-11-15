"""A GRBL 1.1 modal-state interpreter for whole files.

It walks the lexed lines and produces moves (in work coordinates, millimetres), tool changes,
dwells and issues. It does not simulate acceleration. Positions are ``None`` where they can't
be known from the file alone (before the first move, after ``G53``/``G28``/probing).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from ugs_mcp.gcode.lexer import Line, code_name, lex

MM_PER_INCH = 25.4
EPS = 1e-9

Pos = tuple[float | None, float | None, float | None]

G_SUPPORTED = frozenset(
    {
        *(f"G{n}" for n in (0, 1, 2, 3, 4, 10, 17, 18, 19, 20, 21, 28, 30, 40, 49, 53, 61, 80)),
        *(f"G{n}" for n in (54, 55, 56, 57, 58, 59, 90, 91, 92, 93, 94)),
        "G28.1",
        "G30.1",
        "G38.2",
        "G38.3",
        "G38.4",
        "G38.5",
        "G43.1",
        "G91.1",
        "G92.1",
    }
)
M_SUPPORTED = frozenset({"M0", "M1", "M2", "M30", "M3", "M4", "M5", "M7", "M8", "M9", "M56"})
LETTERS_SUPPORTED = frozenset("FGIJKLMNPRSTXYZ")

UNSUPPORTED_REASONS: dict[str, str] = {
    "G41": "cutter radius compensation is not supported by GRBL",
    "G42": "cutter radius compensation is not supported by GRBL",
    "G43": "tool length offsets from a table are not supported by GRBL (only G43.1)",
    "G64": "path blending modes are not supported by GRBL",
    "G90.1": "absolute arc centers are not supported by GRBL",
    "G98": "canned cycles are not supported by GRBL",
    "G99": "canned cycles are not supported by GRBL",
}
for _n in range(81, 90):
    UNSUPPORTED_REASONS[f"G{_n}"] = "canned cycles are not supported by GRBL"

MOTION_CODES = frozenset({"G0", "G1", "G2", "G3", "G38.2", "G38.3", "G38.4", "G38.5", "G80"})
AXIS_CONSUMERS = frozenset({"G10", "G28", "G30", "G92"})
PLANES = {"G17": (0, 1, 2), "G18": (2, 0, 1), "G19": (1, 2, 0)}
"""plane -> (axis_0, axis_1, linear axis), following GRBL's arc convention."""


@dataclass(slots=True)
class Move:
    line: int
    kind: str
    """``rapid``, ``feed``, ``arc`` or ``probe``."""
    start: Pos
    end: Pos
    feed: float | None
    """mm/min (None for rapids); for G93 the inverse-time F value."""
    length: float
    lo: Pos
    hi: Pos
    tool: int | None
    spindle_on: bool
    inverse_time: bool = False

    @property
    def is_cut(self) -> bool:
        return self.kind in ("feed", "arc")

    @property
    def is_plunge(self) -> bool:
        s, e = self.start, self.end
        if s[2] is None or e[2] is None or e[2] >= s[2] - EPS:
            return False
        return all(
            a is None or b is None or abs(a - b) < 1e-6 for a, b in zip(s[:2], e[:2], strict=True)
        )


@dataclass(slots=True)
class MachineMove:
    """A ``G53`` move: targets are machine coordinates."""

    line: int
    target: Pos
    rapid: bool


@dataclass(slots=True)
class ToolChange:
    line: int
    tool: int | None
    has_t: bool
    """A ``T`` word was on the line or came after the previous ``M6``."""


@dataclass(slots=True)
class Issue:
    line: int
    code: str
    """What triggered it: ``G41``, ``A``, ``M6-no-T``, ``syntax``, ``arc``, ..."""
    level: str
    """``error`` (the controller will reject or mis-run it), ``warn`` or ``info``."""
    message: str


@dataclass(slots=True)
class Program:
    lines: list[Line]
    moves: list[Move] = field(default_factory=list)
    machine_moves: list[MachineMove] = field(default_factory=list)
    tool_changes: list[ToolChange] = field(default_factory=list)
    dwells: list[tuple[int, float]] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
    unsupported: dict[str, list[int]] = field(default_factory=dict)
    units_seen: set[str] = field(default_factory=set)
    wcs_seen: set[str] = field(default_factory=set)
    program_end: int | None = None
    final_distance: str = "G90"
    final_spindle_on: bool = False
    first_tool: int | None = None
    tool_by_line: list[int | None] = field(default_factory=list)
    """Tool in the collet at each line (index = line number; index 0 unused)."""


@dataclass(slots=True)
class _State:
    pos: list[float | None] = field(default_factory=lambda: [None, None, None])
    inches: bool = False
    incremental: bool = False
    plane: str = "G17"
    motion: str = "G0"
    feed: float | None = None
    inverse_time: bool = False
    spindle_on: bool = False
    selected_tool: int | None = None
    t_since_m6: bool = False
    tool: int | None = None
    wcs: str = "G54"


def interpret_text(text: str) -> Program:
    return interpret(lex(text))


def interpret(lines: list[Line]) -> Program:
    prog = Program(lines=lines, tool_by_line=[None] * (len(lines) + 1))
    st = _State()
    for line in lines:
        _step(prog, st, line)
        prog.tool_by_line[line.number] = st.tool
    prog.final_distance = "G91" if st.incremental else "G90"
    prog.final_spindle_on = st.spindle_on
    return prog


def _unsupported(prog: Program, line: int, code: str, reason: str) -> None:
    prog.unsupported.setdefault(code, []).append(line)
    if len(prog.unsupported[code]) == 1:
        prog.issues.append(Issue(line, code, "error", f"{code}: {reason}"))


def _step(prog: Program, st: _State, line: Line) -> None:
    n = line.number
    for p in line.problems:
        prog.issues.append(Issue(n, "syntax", "error", f"line {n}: {p}"))
    if line.nested_comment:
        prog.issues.append(
            Issue(
                n,
                "nested-comment",
                "info",
                f"line {n}: nested parentheses in a comment. UGS strips it whole, but GRBL itself"
                " ends a comment at the first ')'.",
            )
        )
    if line.system is not None:
        prog.issues.append(
            Issue(n, "system", "warn", f"line {n}: '{line.system}' is a GRBL system command")
        )
        return
    if not line.words:
        return

    gs: list[str] = []
    ms: list[str] = []
    vals: dict[str, float] = {}
    for letter, value in line.words:
        if letter == "G":
            gs.append(code_name("G", value))
        elif letter == "M":
            ms.append(code_name("M", value))
        else:
            if letter in vals and letter != "N":
                prog.issues.append(
                    Issue(n, "syntax", "error", f"line {n}: '{letter}' appears twice")
                )
            vals[letter] = value
        if letter not in LETTERS_SUPPORTED:
            reason = (
                "rotary or extra axis words aren't supported (3-axis GRBL)"
                if letter in "ABCUVW"
                else f"'{letter}' words aren't GRBL 1.1 G-code"
            )
            _unsupported(prog, n, letter, reason)

    for g in gs:
        if g not in G_SUPPORTED:
            _unsupported(prog, n, g, UNSUPPORTED_REASONS.get(g, "not a GRBL 1.1 G-code"))
    for m in ms:
        if m != "M6" and m not in M_SUPPORTED:
            _unsupported(prog, n, m, "not a GRBL 1.1 M-code")

    # Modal settings that affect how the rest of the line is read.
    if "G20" in gs:
        st.inches = True
        prog.units_seen.add("G20")
    if "G21" in gs:
        st.inches = False
        prog.units_seen.add("G21")
    if "G90" in gs:
        st.incremental = False
    if "G91" in gs:
        st.incremental = True
    for plane in PLANES:
        if plane in gs:
            st.plane = plane
    if "G93" in gs:
        st.inverse_time = True
    if "G94" in gs:
        st.inverse_time = False
    for g in gs:
        if g in ("G54", "G55", "G56", "G57", "G58", "G59"):
            prog.wcs_seen.add(g)
            if g != st.wcs:
                st.wcs = g
                if g != "G54":
                    prog.issues.append(
                        Issue(n, g, "warn", f"line {n}: switches to work offset {g}")
                    )
                st.pos = [None, None, None]

    scale = MM_PER_INCH if st.inches else 1.0
    if "F" in vals:
        st.feed = vals["F"] if st.inverse_time else vals["F"] * scale
    if "T" in vals:
        st.selected_tool = round(vals["T"])
        st.t_since_m6 = True
    if "M6" in ms:
        has_t = "T" in vals or st.t_since_m6
        prog.tool_changes.append(ToolChange(n, st.selected_tool, has_t))
        if not has_t:
            prog.issues.append(
                Issue(
                    n,
                    "M6-no-T",
                    "warn",
                    f"line {n}: M6 without a T word since the previous tool change"
                    + (f" (would load T{st.selected_tool} again)" if st.selected_tool else ""),
                )
            )
        st.tool = st.selected_tool
        if prog.first_tool is None:
            prog.first_tool = st.tool
        st.t_since_m6 = False
    if "M3" in ms or "M4" in ms:
        st.spindle_on = True
    if "M5" in ms:
        st.spindle_on = False
    if "G4" in gs:
        prog.dwells.append((n, vals.get("P", 0.0)))

    axes = [vals.get(a) for a in "XYZ"]
    has_axes = any(v is not None for v in axes)
    consumers = [g for g in gs if g in AXIS_CONSUMERS]
    for g in consumers:
        _non_modal(prog, st, n, g, gs, vals, axes, scale)
    for g in gs:
        if g in MOTION_CODES:
            st.motion = g
    if ("G28.1" in gs or "G30.1" in gs or "G92.1" in gs) and "G92.1" in gs:
        st.pos = [None, None, None]

    if has_axes and not consumers:
        if st.motion == "G80":
            prog.issues.append(
                Issue(n, "syntax", "error", f"line {n}: axis words with no motion mode (G80)")
            )
        elif "G53" in gs:
            _machine_move(prog, st, n, axes, scale)
        else:
            _motion(prog, st, n, vals, axes, scale)
    elif "G53" in gs and not has_axes:
        prog.issues.append(Issue(n, "syntax", "warn", f"line {n}: G53 without axis words"))

    for m in ms:
        if m in ("M2", "M30") and prog.program_end is None:
            prog.program_end = n
        if m in ("M2", "M30"):
            st.incremental = False
            st.plane = "G17"
            st.inverse_time = False
            st.spindle_on = False
            st.motion = "G1"
            st.wcs = "G54"


def _non_modal(
    prog: Program,
    st: _State,
    n: int,
    g: str,
    gs: list[str],
    vals: dict[str, float],
    axes: list[float | None],
    scale: float,
) -> None:
    if g == "G10":
        prog.issues.append(
            Issue(n, "G10", "warn", f"line {n}: G10 changes a work offset from inside the file")
        )
        st.pos = [None, None, None]
    elif g in ("G28", "G30"):
        prog.issues.append(
            Issue(n, g, "warn", f"line {n}: {g} moves to a stored position (unknown here)")
        )
        st.pos = [None, None, None]
    elif g == "G92":
        prog.issues.append(
            Issue(n, "G92", "warn", f"line {n}: G92 shifts the coordinate system from the file")
        )
        for i, v in enumerate(axes):
            if v is not None:
                st.pos[i] = v * scale


def _machine_move(
    prog: Program, st: _State, n: int, axes: list[float | None], scale: float
) -> None:
    target: Pos = (
        None if axes[0] is None else axes[0] * scale,
        None if axes[1] is None else axes[1] * scale,
        None if axes[2] is None else axes[2] * scale,
    )
    prog.machine_moves.append(MachineMove(n, target, rapid=st.motion == "G0"))
    for i, v in enumerate(axes):
        if v is not None:
            st.pos[i] = None


def _target(st: _State, axes: list[float | None], scale: float) -> list[float | None]:
    out: list[float | None] = list(st.pos)
    for i, v in enumerate(axes):
        if v is None:
            continue
        if st.incremental:
            cur = st.pos[i]
            out[i] = None if cur is None else cur + v * scale
        else:
            out[i] = v * scale
    return out


def _bounds(start: list[float | None], end: list[float | None]) -> tuple[Pos, Pos, float]:
    lo: list[float | None] = []
    hi: list[float | None] = []
    sq = 0.0
    for a, b in zip(start, end, strict=True):
        if b is None:
            lo.append(None)
            hi.append(None)
            continue
        if a is None:
            lo.append(b)
            hi.append(b)
            continue
        lo.append(min(a, b))
        hi.append(max(a, b))
        sq += (b - a) ** 2
    return _pos(lo), _pos(hi), math.sqrt(sq)


def _pos(v: list[float | None]) -> Pos:
    return (v[0], v[1], v[2])


def _motion(
    prog: Program,
    st: _State,
    n: int,
    vals: dict[str, float],
    axes: list[float | None],
    scale: float,
) -> None:
    start = list(st.pos)
    end = _target(st, axes, scale)
    mode = st.motion
    if mode in ("G1", "G2", "G3") and (st.feed is None or st.feed <= 0):
        prog.issues.append(
            Issue(n, "no-feed", "error", f"line {n}: {mode} with no feed rate (GRBL error:22)")
        )
    feed = None if mode == "G0" else st.feed
    if mode in ("G2", "G3"):
        arc = _arc(prog, st, n, mode, vals, start, end, scale)
        if arc is None:
            lo, hi, length = _bounds(start, end)
        else:
            lo, hi, length = arc
        kind = "arc"
    else:
        lo, hi, length = _bounds(start, end)
        kind = "rapid" if mode == "G0" else ("feed" if mode == "G1" else "probe")
    prog.moves.append(
        Move(
            line=n,
            kind=kind,
            start=_pos(start),
            end=_pos(end),
            feed=feed,
            length=length,
            lo=lo,
            hi=hi,
            tool=st.tool,
            spindle_on=st.spindle_on,
            inverse_time=st.inverse_time,
        )
    )
    if kind == "probe":
        prog.issues.append(Issue(n, mode, "warn", f"line {n}: {mode} probing move in the file"))
        for i, v in enumerate(axes):
            if v is not None:
                end[i] = None
    st.pos = end


def _arc(
    prog: Program,
    st: _State,
    n: int,
    mode: str,
    vals: dict[str, float],
    start: list[float | None],
    end: list[float | None],
    scale: float,
) -> tuple[Pos, Pos, float] | None:
    a0, a1, lin = PLANES[st.plane]
    s0, s1, e0, e1 = start[a0], start[a1], end[a0], end[a1]
    if s0 is None or s1 is None or e0 is None or e1 is None:
        prog.issues.append(
            Issue(n, "arc", "warn", f"line {n}: arc starts from an unknown position")
        )
        return None
    cw = mode == "G2"
    offset_letters = "IJK"
    if "R" in vals:
        r = vals["R"] * scale
        x, y = e0 - s0, e1 - s1
        d = math.hypot(x, y)
        h2 = 4 * r * r - x * x - y * y
        if d < EPS:
            prog.issues.append(
                Issue(n, "arc", "error", f"line {n}: R-format full circle (GRBL error:33)")
            )
            return None
        if h2 < -1e-6:
            prog.issues.append(
                Issue(n, "arc", "error", f"line {n}: arc radius too small (GRBL error:33)")
            )
            return None
        h = -math.sqrt(max(h2, 0.0)) / d
        if not cw:
            h = -h
        if r < 0:
            h = -h
        c0 = s0 + 0.5 * (x - y * h)
        c1 = s1 + 0.5 * (y + x * h)
    else:
        i0 = vals.get(offset_letters[a0], 0.0) * scale
        i1 = vals.get(offset_letters[a1], 0.0) * scale
        if abs(i0) < EPS and abs(i1) < EPS:
            prog.issues.append(
                Issue(n, "arc", "error", f"line {n}: arc with no center offset (GRBL error:26)")
            )
            return None
        c0, c1 = s0 + i0, s1 + i1
    radius = math.hypot(s0 - c0, s1 - c1)
    end_radius = math.hypot(e0 - c0, e1 - c1)
    if abs(radius - end_radius) > 0.005 and abs(radius - end_radius) > 0.001 * radius:
        prog.issues.append(
            Issue(
                n,
                "arc",
                "error",
                f"line {n}: arc end is {abs(radius - end_radius):.3f} mm off the circle"
                " (GRBL error:33)",
            )
        )
    t0 = math.atan2(s1 - c1, s0 - c0)
    t1 = math.atan2(e1 - c1, e0 - c0)
    sweep = (t0 - t1) if cw else (t1 - t0)
    sweep %= 2 * math.pi
    if sweep < 1e-9:
        sweep = 2 * math.pi
    pts0 = [s0, e0]
    pts1 = [s1, e1]
    for k in range(4):
        theta = k * math.pi / 2
        rel = (t0 - theta) if cw else (theta - t0)
        if rel % (2 * math.pi) <= sweep + 1e-12:
            pts0.append(c0 + radius * math.cos(theta))
            pts1.append(c1 + radius * math.sin(theta))
    lo: list[float | None] = [None, None, None]
    hi: list[float | None] = [None, None, None]
    lo[a0], hi[a0] = min(pts0), max(pts0)
    lo[a1], hi[a1] = min(pts1), max(pts1)
    sl, el = start[lin], end[lin]
    dl = 0.0
    if el is not None:
        lo[lin] = el if sl is None else min(sl, el)
        hi[lin] = el if sl is None else max(sl, el)
        dl = 0.0 if sl is None else el - sl
    return _pos(lo), _pos(hi), math.hypot(radius * sweep, dl)

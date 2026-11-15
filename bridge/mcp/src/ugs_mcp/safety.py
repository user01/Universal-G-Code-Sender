"""Authority classification of command lines (bridge/docs/api.md, "Authority classification").

The Java bridge enforces this table; the MCP server applies it again before sending anything,
plus the extra checks of SPEC §5.2 (homed, envelope, bounded probes, modal hygiene). Work
package C only ships the classification; the motion checks arrive with package H.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum

from ugs_mcp.gcode.lexer import split_comments


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

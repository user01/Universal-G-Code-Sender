"""Split one G-code line into comments, words and system commands.

Pure functions, no state. The interpreter in :mod:`ugs_mcp.gcode.interpreter` consumes the
result. Comments follow UGS rather than GRBL for nesting: UGS strips ``(...)`` greedily, so a
comment like ``(T3 6.35 mm (0.25 in) feed 2032)`` is removed whole, but the line is flagged
because a sender that ends comments at the first ``)`` would send the rest.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

_WORD = re.compile(r"([A-Z])([-+]?(?:\d+\.?\d*|\.\d+))")


@dataclass(slots=True)
class Line:
    """One source line. ``number`` is 1-based, as editors and UGS show it."""

    number: int
    raw: str
    code: str
    """Uppercase, comments and whitespace removed."""
    comments: list[str] = field(default_factory=list)
    words: list[tuple[str, float]] = field(default_factory=list)
    tokens: list[str] = field(default_factory=list)
    """Words as written (``G01``, ``X-1.5``), parallel to ``words``."""
    system: str | None = None
    """``$``-commands (``$H``, ``$J=...``), which are not G-code."""
    problems: list[str] = field(default_factory=list)
    nested_comment: bool = False

    @property
    def is_blank(self) -> bool:
        return not self.code and not self.comments

    @property
    def is_comment_only(self) -> bool:
        return not self.code and bool(self.comments)

    @property
    def comment(self) -> str | None:
        """The first comment, or None."""
        return self.comments[0] if self.comments else None


def split_comments(raw: str) -> tuple[str, list[str], bool]:
    """Return (code, comments, nested). Parenthesized comments may nest; ``;`` ends the line."""
    code: list[str] = []
    comments: list[str] = []
    current: list[str] = []
    depth = 0
    nested = False
    for i, ch in enumerate(raw):
        if depth == 0:
            if ch == "(":
                depth = 1
                current = []
            elif ch == ";":
                comments.append(raw[i + 1 :].strip())
                break
            else:
                code.append(ch)
        elif ch == "(":
            depth += 1
            nested = True
            current.append(ch)
        elif ch == ")":
            depth -= 1
            if depth == 0:
                comments.append("".join(current))
            else:
                current.append(ch)
        else:
            current.append(ch)
    if depth > 0:
        comments.append("".join(current))
    return "".join(code), comments, nested


def lex_line(number: int, raw: str) -> Line:
    text = raw.rstrip("\r\n")
    code_part, comments, nested = split_comments(text)
    code = "".join(code_part.split()).upper()
    line = Line(number=number, raw=text, code=code, comments=comments, nested_comment=nested)
    if depth_unclosed(text):
        line.problems.append("unclosed '(' comment")
    if not code or code == "%":
        line.code = ""
        return line
    if code.startswith("$"):
        line.system = code
        return line
    pos = 0
    for m in _WORD.finditer(code):
        if m.start() != pos:
            line.problems.append(f"unreadable text {code[pos : m.start()]!r}")
        line.words.append((m.group(1), float(m.group(2))))
        line.tokens.append(m.group(0))
        pos = m.end()
    if pos != len(code):
        line.problems.append(f"unreadable text {code[pos:]!r}")
    return line


def depth_unclosed(text: str) -> bool:
    depth = 0
    for ch in text:
        if ch == ";" and depth == 0:
            return False
        if ch == "(":
            depth += 1
        elif ch == ")" and depth > 0:
            depth -= 1
    return depth > 0


def lex(text: str) -> list[Line]:
    return [lex_line(i, raw) for i, raw in enumerate(text.splitlines(), start=1)]


def code_name(letter: str, value: float) -> str:
    """Normalize a G/M word: ``G00`` -> ``G0``, ``G38.2`` stays, ``M06`` -> ``M6``."""
    tenths = round(value * 10)
    if tenths % 10 == 0:
        return f"{letter}{tenths // 10}"
    return f"{letter}{tenths // 10}.{tenths % 10}"

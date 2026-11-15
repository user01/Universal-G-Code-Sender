"""``prepare_file``: write an edited sibling copy of a G-code file and return a unified diff.

The original is never opened for writing. Edits address lines by their number in the
**original** file (1-based), so their order doesn't change what they refer to.

Edit vocabulary (each edit is a JSON object with ``op``):

``{"op": "remove_m6"}``
    Drop ``M6`` words (keeps ``T`` words and the rest of the line).
``{"op": "comment_out", "lines": [first, last]}``
    Prefix each line with ``;`` (GRBL and UGS treat it as a comment).
``{"op": "comment_out_unsupported"}``
    Comment out every line the analysis lists as unsupported.
``{"op": "delete", "lines": [first, last]}``
``{"op": "replace", "line": n, "text": "G0 Z5"}``
``{"op": "insert_before" | "insert_after", "line": n, "text": "..."}``
    ``text`` may contain several lines separated by newlines.
``{"op": "regex", "pattern": "...", "replacement": "...", "lines"?: [first, last]}``
    Python ``re.sub`` on the code of each line (comments included).
"""

from __future__ import annotations

import difflib
import hashlib
import os
import re
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from ugs_mcp.gcode.interpreter import interpret
from ugs_mcp.gcode.lexer import lex

MAX_DIFF_LINES = 300
_M6 = re.compile(r"(?i)(?<![A-Z])M0*6(?![0-9.])\s*")


class PrepareError(ValueError):
    pass


def default_output(path: Path) -> Path:
    return path.with_name(f"{path.stem}.ugs{path.suffix or '.nc'}")


def _range(edit: dict[str, Any], n_lines: int) -> tuple[int, int]:
    if "lines" in edit:
        rng = edit["lines"]
        if not (isinstance(rng, list | tuple) and len(rng) == 2):
            raise PrepareError(f"{edit['op']}: 'lines' must be [first, last]")
        a, b = int(rng[0]), int(rng[1])
    elif "line" in edit:
        a = b = int(edit["line"])
    else:
        raise PrepareError(f"{edit['op']}: needs 'line' or 'lines'")
    if not (1 <= a <= b <= n_lines):
        raise PrepareError(f"{edit['op']}: lines {a}..{b} are outside 1..{n_lines}")
    return a, b


def apply_edits(text: str, edits: Sequence[dict[str, Any]]) -> tuple[list[str], list[str]]:
    """Return (original lines, edited lines)."""
    original = text.splitlines()
    n = len(original)
    out: list[list[str]] = [[ln] for ln in original]
    before: list[list[str]] = [[] for _ in original]
    after: list[list[str]] = [[] for _ in original]
    for edit in edits:
        if not isinstance(edit, dict) or "op" not in edit:
            raise PrepareError(f"each edit needs an 'op': {edit!r}")
        op = edit["op"]
        if op == "remove_m6":
            for i, cur in enumerate(out):
                out[i] = [_remove_m6(s) for s in cur]
        elif op == "comment_out":
            a, b = _range(edit, n)
            for i in range(a - 1, b):
                out[i] = [s if s.lstrip().startswith(";") else f";{s}" for s in out[i]]
        elif op == "comment_out_unsupported":
            prog = interpret(lex(text))
            for lines in prog.unsupported.values():
                for ln in lines:
                    out[ln - 1] = [
                        s if s.lstrip().startswith(";") else f";{s}" for s in out[ln - 1]
                    ]
        elif op == "delete":
            a, b = _range(edit, n)
            for i in range(a - 1, b):
                out[i] = []
        elif op == "replace":
            a, b = _range(edit, n)
            new = str(edit.get("text", "")).splitlines()
            out[a - 1] = new
            for i in range(a, b):
                out[i] = []
        elif op in ("insert_before", "insert_after"):
            a, _ = _range(edit, n)
            new = str(edit.get("text", "")).splitlines()
            (before if op == "insert_before" else after)[a - 1].extend(new)
        elif op == "regex":
            try:
                pattern = re.compile(str(edit["pattern"]))
            except (KeyError, re.error) as exc:
                raise PrepareError(f"regex: bad pattern: {exc}") from exc
            repl = str(edit.get("replacement", ""))
            a, b = _range(edit, n) if "lines" in edit else (1, n)
            for i in range(a - 1, b):
                out[i] = [pattern.sub(repl, s) for s in out[i]]
        else:
            raise PrepareError(
                f"unknown edit op {op!r}; use remove_m6, comment_out, comment_out_unsupported,"
                " delete, replace, insert_before, insert_after or regex"
            )
    edited: list[str] = []
    for i in range(n):
        edited.extend(before[i])
        edited.extend(out[i])
        edited.extend(after[i])
    return original, edited


def _remove_m6(line: str) -> str:
    code, sep, comment = _split_code(line)
    new = _M6.sub("", code).rstrip()
    if new == code.rstrip():
        return line
    if not new.strip() and not comment:
        return ""
    return new + (" " if new and sep else "") + sep + comment


def _split_code(line: str) -> tuple[str, str, str]:
    """Split at the first comment start, so edits leave comments alone."""
    for i, ch in enumerate(line):
        if ch in "(;":
            return line[:i], ch, line[i + 1 :]
    return line, "", ""


def prepare(
    path: Path,
    edits: Sequence[dict[str, Any]],
    output: Path | None = None,
    *,
    overwrite: bool = False,
) -> dict[str, Any]:
    path = path.expanduser().resolve()
    if not path.is_file():
        raise PrepareError(f"{path} is not a file")
    out_path = output.expanduser() if output else default_output(path)
    if not out_path.is_absolute():
        out_path = path.parent / out_path
    out_path = out_path.resolve()
    if out_path.parent != path.parent:
        raise PrepareError("the prepared copy must be a sibling of the original (same folder)")
    if out_path == path or (out_path.exists() and os.path.samefile(out_path, path)):
        raise PrepareError("refusing to write over the original file")
    if out_path.exists() and not overwrite:
        raise PrepareError(f"{out_path.name} already exists; pass overwrite=true to replace it")
    if not edits:
        raise PrepareError("no edits given")

    raw = path.read_bytes()
    text = raw.decode("utf-8", errors="replace")
    newline = "\r\n" if "\r\n" in text else "\n"
    original, edited = apply_edits(text, edits)
    body = newline.join(edited) + (newline if text.endswith(("\n", "\r")) else "")
    tmp = out_path.with_name(out_path.name + ".tmp")
    tmp.write_text(body, encoding="utf-8", newline="")
    tmp.replace(out_path)

    diff = list(
        difflib.unified_diff(
            original, edited, fromfile=path.name, tofile=out_path.name, lineterm="", n=1
        )
    )
    changed = sum(1 for d in diff if d[:1] in "+-" and not d.startswith(("+++", "---")))
    truncated = len(diff) > MAX_DIFF_LINES
    data = body.encode("utf-8")
    remaining = interpret(lex(body))
    return {
        "original": str(path),
        "output": str(out_path),
        "sha256": hashlib.sha256(data).hexdigest(),
        "lines": len(edited),
        "original_lines": len(original),
        "changed_diff_lines": changed,
        "diff": "\n".join(diff[:MAX_DIFF_LINES])
        + (f"\n... diff truncated ({len(diff) - MAX_DIFF_LINES} more lines)" if truncated else ""),
        "remaining_unsupported": sorted(remaining.unsupported),
        "m6_remaining": len(remaining.tool_changes),
        "note": "line numbers in a sidecar or the post headers refer to the original file"
        if len(edited) != len(original)
        else "",
    }

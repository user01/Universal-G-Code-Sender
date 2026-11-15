"""Validate session-log JSONL files against bridge/schema/log-record.schema.json.

Usage (from bridge/):  uv run python scripts/validate_log.py <session.jsonl> [...]

Checks every line against the record schema (with status.schema.json resolved for `status` records)
and that `seq` is strictly increasing within each file. Exit code 0 when everything is valid.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

SCHEMA_DIR = Path(__file__).resolve().parent.parent / "schema"
T_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})$")


def build_validator() -> Draft202012Validator:
    record = json.loads((SCHEMA_DIR / "log-record.schema.json").read_text())
    status = json.loads((SCHEMA_DIR / "status.schema.json").read_text())
    status_res = Resource.from_contents(status)
    registry = Registry().with_resources(
        [
            (status["$id"], status_res),
            # log-record refers to "status.schema.json", resolved relative to its own $id
            ("ugs-bridge/log-record/status.schema.json", status_res),
            ("status.schema.json", status_res),
        ]
    )
    return Draft202012Validator(record, registry=registry, format_checker=FormatChecker())


def validate_file(validator: Draft202012Validator, path: Path) -> list[str]:
    errors: list[str] = []
    last_seq = 0
    kinds: set[str] = set()
    lines = path.read_text().splitlines()
    for n, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError as e:
            errors.append(f"{path}:{n}: not JSON: {e}")
            continue
        for err in validator.iter_errors(rec):
            loc = "/".join(str(p) for p in err.absolute_path)
            errors.append(f"{path}:{n}: {loc}: {err.message}")
        # format "date-time" is only checked by jsonschema when rfc3339-validator is installed
        if not T_PATTERN.match(str(rec.get("t", ""))):
            errors.append(f"{path}:{n}: t {rec.get('t')!r} is not ISO 8601 with ms and offset")
        seq = rec.get("seq")
        if isinstance(seq, int):
            if seq <= last_seq:
                errors.append(f"{path}:{n}: seq {seq} not greater than {last_seq}")
            last_seq = seq
        kinds.add(str(rec.get("kind")))
    print(f"{path}: {len(lines)} lines, kinds={sorted(kinds)}")
    return errors


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    validator = build_validator()
    errors: list[str] = []
    for arg in argv:
        errors += validate_file(validator, Path(arg))
    for e in errors:
        print(e, file=sys.stderr)
    print("OK" if not errors else f"{len(errors)} error(s)")
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

"""Validate the JSON samples written by JsonSamplesTest against the bridge schemas.

Run from bridge/ (uv env with jsonschema):
    uv run python ../ugs-platform/ugs-platform-plugin-bridge/src/test/python/validate_json_samples.py \
        ../ugs-platform/ugs-platform-plugin-bridge/target/json-samples
"""

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

SCHEMA_DIR = Path(__file__).resolve().parents[5] / "bridge" / "schema"


def load(name: str) -> dict:
    return json.loads((SCHEMA_DIR / name).read_text())


def main(sample_dir: str) -> int:
    job_state = load("job-state.schema.json")
    schemas = {
        "job-state": job_state,
        "resume-plan": job_state["$defs"]["ResumePlan"],
        "origins": load("origins.schema.json"),
    }
    failures = 0
    files = sorted(Path(sample_dir).glob("*.json"))
    if not files:
        print(f"no samples in {sample_dir}")
        return 1
    for f in files:
        key = next(k for k in schemas if f.name.startswith(k))
        validator = Draft202012Validator(schemas[key], format_checker=Draft202012Validator.FORMAT_CHECKER)
        errors = list(validator.iter_errors(json.loads(f.read_text())))
        for e in errors:
            print(f"FAIL {f.name}: {'/'.join(map(str, e.absolute_path))}: {e.message}")
        if not errors:
            print(f"ok   {f.name} ({key})")
        failures += len(errors)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))

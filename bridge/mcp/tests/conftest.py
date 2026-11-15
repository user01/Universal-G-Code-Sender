from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

from ugs_mcp.testing.fake_bridge import FakeBridge

BRIDGE = Path(__file__).resolve().parents[2]
SCHEMA_DIR = BRIDGE / "schema"
TESTDATA = BRIDGE / "testdata" / "nc"


def _registry() -> Registry:
    resources = []
    for f in SCHEMA_DIR.glob("*.schema.json"):
        schema = json.loads(f.read_text())
        res = Resource.from_contents(schema)
        resources.append((schema["$id"], res))
        # log-record refers to "status.schema.json" relative to its own $id.
        resources.append((f"ugs-bridge/log-record/{f.name}", res))
    return Registry().with_resources(resources)


REGISTRY = _registry()


def validator(name: str) -> Draft202012Validator:
    schema = json.loads((SCHEMA_DIR / f"{name}.schema.json").read_text())
    return Draft202012Validator(schema, registry=REGISTRY)


@pytest.fixture(scope="session")
def validate() -> Callable[[str, Any], None]:
    cache: dict[str, Draft202012Validator] = {}

    def check(name: str, instance: Any) -> None:
        v = cache.setdefault(name, validator(name))
        errors = sorted(v.iter_errors(instance), key=lambda e: list(e.path))
        assert not errors, f"{name}: " + "; ".join(
            f"{list(e.path)}: {e.message}" for e in errors[:5]
        )

    return check


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    h = tmp_path / "ugs-bridge-home"
    h.mkdir()
    monkeypatch.setenv("UGS_BRIDGE_HOME", str(h))
    return h


@pytest.fixture
def fake(home: Path, validate: Callable[[str, Any], None]) -> Iterator[FakeBridge]:
    bridge = FakeBridge(home)
    with bridge:
        yield bridge
    for rec in bridge.records:
        validate("log-record", rec)


def testdata(name: str) -> Path:
    return TESTDATA / name


needs_testdata = pytest.mark.skipif(
    not TESTDATA.is_dir() or not any(TESTDATA.glob("*.nc")),
    reason="bridge/testdata/nc is not present (gitignored personal files)",
)

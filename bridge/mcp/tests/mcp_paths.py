"""Shared test paths for the mcp tests.

Kept out of conftest.py: bridge/sim/tests has its own conftest, and importing
`conftest` by name picks whichever one pytest loaded first.
"""

from __future__ import annotations

from pathlib import Path

import pytest

BRIDGE = Path(__file__).resolve().parents[2]
SCHEMA_DIR = BRIDGE / "schema"
TESTDATA = BRIDGE / "testdata" / "nc"

needs_testdata = pytest.mark.skipif(
    not TESTDATA.is_dir() or not any(TESTDATA.glob("*.nc")),
    reason="bridge/testdata/nc is not present (gitignored personal files)",
)

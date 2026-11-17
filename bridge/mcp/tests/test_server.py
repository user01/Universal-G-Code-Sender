"""MCP tool round-trips: in-process MCP client -> ugs-mcp server -> HTTP -> fake bridge."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import pytest
from mcp import Client

from ugs_mcp.bridge_client import BridgeClient
from ugs_mcp.server import build_server
from ugs_mcp.testing.fake_bridge import FakeBridge

pytestmark = pytest.mark.anyio

EXPECTED_TOOLS = {
    "status",
    "console",
    "log_query",
    "settings",
    "query",
    "notify",
    "request_authority",
    "analyze_file",
    "prepare_file",
    # Work package H: motion, origins, resume, stream.
    "move",
    "jog",
    "probe",
    "send",
    "set_work_offset",
    "hold",
    "resume",
    "reset",
    "origins",
    "apply_origin",
    "job_state",
    "plan_resume",
    "start_resume",
    "run_file",
}


def _text(result: Any) -> str:
    return "\n".join(c.text for c in result.content if getattr(c, "type", "") == "text")


async def _call(client: Client, name: str, args: dict[str, Any] | None = None) -> tuple[str, bool]:
    result = await client.call_tool(name, args or {})
    return _text(result), bool(result.is_error)


async def test_tool_list_is_exact(home: Path) -> None:
    async with Client(build_server(BridgeClient(home))) as client:
        names = {t.name for t in (await client.list_tools()).tools}
    assert names == EXPECTED_TOOLS


async def test_observe_and_query_round_trip(fake: FakeBridge, home: Path) -> None:
    async with Client(build_server(BridgeClient(home))) as client:
        text, err = await _call(client, "status")
        assert not err
        assert "State: Alarm" in text
        assert "Claude access: unlocked, level query" in text
        assert "Homed this connection: NO" in text
        assert "no inputs active" in text

        text, err = await _call(client, "settings")
        assert not err
        assert "No $$ output" in text

        text, err = await _call(client, "query", {"cmd": "$$"})
        assert not err
        assert "$130=440.000" in text

        text, err = await _call(client, "settings")
        assert not err
        assert "X max travel" in text
        assert "MISMATCH: profile expects 445" in text  # the simulator says 440
        assert "$132" in text

        text, err = await _call(client, "query", {"cmd": " $i "})
        assert not err
        assert "[VER:" in text

        text, err = await _call(client, "query", {"cmd": "?"})
        assert not err
        assert text.startswith("<Alarm")

        text, err = await _call(client, "query", {"cmd": "G0 X10"})
        assert err
        assert "only sends" in text
        text, err = await _call(client, "query", {"cmd": "$130=845"})
        assert err
        assert "Setting writes are refused" in text

        text, err = await _call(client, "console", {"since": 0})
        assert not err
        assert "> $$" in text
        assert "< ok" in text
        assert text.splitlines()[-1].startswith("next since=")

        text, err = await _call(client, "log_query", {"kinds": ["rx"], "grep": "VER"})
        assert not err
        assert text.startswith("1 record(s) matching /VER/")
        text, err = await _call(client, "log_query", {"kinds": ["nope"]})
        assert err
        assert "unknown kinds" in text
        text, err = await _call(client, "log_query", {"grep": "("})
        assert err
        assert "regular expression" in text

        text, err = await _call(client, "notify", {"text": "job done", "level": "info"})
        assert not err
        assert "queued" in text
        text, err = await _call(client, "notify", {"text": "x", "level": "loud"})
        assert err

        text, err = await _call(
            client, "request_authority", {"level": "motion", "reason": "probe Z on the plate"}
        )
        assert not err
        assert "Nothing changes until" in text
        text, err = await _call(client, "request_authority", {"level": "query", "reason": "x"})
        assert err

    notify = [r for r in fake.records if r["kind"] == "notify"]
    assert notify[0]["data"]["text"] == "job done"
    req = [r for r in fake.records if r["kind"] == "authority" and r["src"] == "claude"]
    assert req[0]["data"]["requested"] == "motion"
    assert fake.level.value == "query"  # asking never grants


async def test_lock_gives_plain_message(fake: FakeBridge, home: Path) -> None:
    fake.set_locked(True)
    async with Client(build_server(BridgeClient(home))) as client:
        for tool in ("status", "console", "settings"):  # reads work while locked
            text, err = await _call(client, tool, {})
            assert not err, (tool, text)
        text, _ = await _call(client, "status", {})
        assert "LOCKED" in text
        for tool, args in [
            ("query", {"cmd": "$$"}),
            ("notify", {"text": "hi"}),
        ]:
            text, err = await _call(client, tool, args)
            assert err, tool
            assert text.endswith("UGS access is locked by the operator"), text
            assert "Traceback" not in text
    assert not [r for r in fake.records if r["kind"] in ("tx", "notify")]


async def test_bridge_not_running(home: Path) -> None:
    async with Client(build_server(BridgeClient(home))) as client:
        text, err = await _call(client, "status")
    assert err
    assert "UGS bridge isn't running" in text


async def test_file_tools_round_trip(fake: FakeBridge, home: Path, tmp_path: Path) -> None:
    nc = tmp_path / "part.nc"
    nc.write_text(
        "(STOCK: 100 x 50 x 10 mm pine)\n(ZERO: X0 Y0 = stock front-left corner, Z0 = stock top)\n"
        "G21 G90\nT1 M6\nM3\nG0 X0 Y0 Z5\nG1 Z-2 F300\nG2 X20 Y0 I10 J0 F800\nG0 Z5\nM5\nM2\n"
    )
    digest = hashlib.sha256(nc.read_bytes()).hexdigest()
    async with Client(build_server(BridgeClient(home))) as client:
        result = await client.call_tool("analyze_file", {"path": str(nc)})
        assert not result.is_error
        data = result.structured_content or json.loads(_text(result))
        assert data["summary"].startswith("part.nc:")
        assert data["file"]["sha256"] == digest
        assert data["extents"]["cut"]["y"] == [0, 10]  # CW from 180 deg passes 90 deg
        assert data["profile"] == "the bridge's active profile"
        assert data["work_offset_used"] is None  # not homed

        text, err = await _call(client, "analyze_file", {"path": str(tmp_path / "missing.nc")})
        assert err
        assert "not a readable file" in text

        result = await client.call_tool(
            "prepare_file", {"path": str(nc), "edits": [{"op": "remove_m6"}]}
        )
        assert not result.is_error
        prepared = result.structured_content or json.loads(_text(result))
        assert prepared["output"].endswith("part.ugs.nc")
        assert "-T1 M6" in prepared["diff"]

        text, err = await _call(
            client, "prepare_file", {"path": str(nc), "edits": [{"op": "x"}], "overwrite": True}
        )
        assert err
        assert "unknown edit op" in text
        text, err = await _call(
            client,
            "prepare_file",
            {"path": str(nc), "edits": [{"op": "remove_m6"}], "output": str(nc)},
        )
        assert err
        assert "original" in text
    assert hashlib.sha256(nc.read_bytes()).hexdigest() == digest


ESTIMATE = {
    "version": 1,
    "source": "grbl-planner",
    "settings_from": "controller",
    "total_s": 2520.0,
    "machine_s": 2460.0,
    "bitsetter_s": 60.0,
    "naive_s": 2200.0,
    "tools": [
        {"tool": "T2", "number": 2, "name": "1/4 in downcut", "s": 1260.0},
        {"tool": "T21", "number": 21, "name": None, "s": 1200.0},
    ],
    "ops": [
        {
            "index": 1,
            "name": "rough",
            "tool": "T2",
            "first_line": 5,
            "last_line": 90,
            "start_s": 0.0,
            "end_s": 1260.0,
        },
    ],
    "tool_changes": [{"line": 4, "from": None, "to": "T2", "at_s": 0.0, "bitsetter_s": 30.0}],
    "assumptions": ["planner look-ahead 14 blocks, buffer kept full by the sender"],
    "line_end_s": [0.0, 1.0],
}


async def test_analyze_uses_the_bridge_planner_estimate_for_the_loaded_file(
    fake: FakeBridge, home: Path, tmp_path: Path
) -> None:
    nc = tmp_path / "part.nc"
    nc.write_text("G21 G90\nT2 M6\nG0 X0 Y0 Z5\nG1 Z-1 F300\nG1 X50 F1000\nM2\n")
    digest = hashlib.sha256(nc.read_bytes()).hexdigest()
    fake.file_estimate = ESTIMATE
    async with Client(build_server(BridgeClient(home))) as client:
        # another file is loaded in UGS: only the simple estimate
        fake.file_info = {"path": "/other.nc", "sha256": "0" * 64, "lines": 3}
        data = (await client.call_tool("analyze_file", {"path": str(nc)})).structured_content
        assert data is not None
        assert data["time"]["planner"] is None
        assert "load this file in UGS" in data["time"]["note"]
        # this file is loaded: the planner estimate is added
        fake.file_info = {"path": str(nc), "sha256": digest, "lines": 6}
        data = (await client.call_tool("analyze_file", {"path": str(nc)})).structured_content
        assert data is not None
        planner = data["time"]["planner"]
        assert planner["machine_min"] == 41.0
        assert planner["tool_change_auto_min"] == 1.0
        assert planner["settings_from"] == "controller"
        assert planner["tools"][0] == {"tool": "T2", "name": "1/4 in downcut", "min": 21.0}
        assert planner["ops"][0]["min"] == 21.0
        assert (
            "Planner estimate (bridge, GRBL accelerations, settings from the controller): 41 min"
            " of machine time + 1 tool change (≈ 60 s automatic, plus the operator's time);"
            " per tool: T2 21 min, T21 20 min." in data["summary"]
        )


async def test_analyze_works_offline(home: Path, tmp_path: Path) -> None:
    nc = tmp_path / "a.nc"
    nc.write_text("G0 X0 Y0 Z5\nG1 Z-1 F100\nM2\n")
    async with Client(build_server(BridgeClient(home))) as client:
        result = await client.call_tool("analyze_file", {"path": str(nc)})
    assert not result.is_error
    data = result.structured_content or {}
    assert data["profile"] is not None  # falls back to the repository profile

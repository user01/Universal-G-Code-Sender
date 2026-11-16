"""Motion tool round-trips: MCP client -> ugs-mcp -> HTTP -> fake bridge -> ShapeokoGrbl.

The simulator (``bridge/sim``, ``ugs_sim.machine.ShapeokoGrbl``) models the BitSetter and the
BitZero from the machine profile: plate 13 mm (sourced, ledger B1), default tool 40 mm, stock
top at tool-tip Z -95.6, BitSetter top at tip Z -105 at X-4 Y-449. Time runs instantly
(``time_scale=inf``) except where a test needs motion in progress.
"""

from __future__ import annotations

import json
import math
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator
from mcp import Client
from mcp_paths import SCHEMA_DIR

from ugs_mcp.bridge_client import BridgeClient, BridgeForbiddenError
from ugs_mcp.config import local_profile
from ugs_mcp.server import build_server
from ugs_mcp.testing.fake_bridge import FakeBridge
from ugs_sim.machine import ShapeokoGrbl
from ugs_sim.profile import load_profile

pytestmark = pytest.mark.anyio

STOCK_XY = {"x": -300.0, "y": -370.0}
BITZERO_CONTACT_Z = -42.6
"""Spindle Z at contact: stock top -95.6 + plate 13 + tool 40."""
BITSETTER_CONTACT_Z = -65.0
"""Spindle Z at contact: BitSetter top -105 + tool 40."""


def _sim() -> ShapeokoGrbl:
    return ShapeokoGrbl(load_profile(), time_scale=math.inf)


@pytest.fixture
def sim_fake(home: Path, validate: Callable[[str, Any], None]) -> Iterator[FakeBridge]:
    bridge = FakeBridge(home, sim=_sim())
    with bridge:
        yield bridge
    for rec in bridge.records:
        validate("log-record", rec)


def _text(result: Any) -> str:
    return "\n".join(c.text for c in result.content if getattr(c, "type", "") == "text")


async def _call(client: Client, name: str, args: dict[str, Any] | None = None) -> tuple[str, bool]:
    result = await client.call_tool(name, args or {})
    return _text(result), bool(result.is_error)


def _client(home: Path) -> Client:
    return Client(build_server(BridgeClient(home)))


def _armed_and_homed(fake: FakeBridge) -> None:
    fake.set_level("motion")
    assert fake.operator_home()["state"] == "done"
    assert fake.homed


def _claude_tx(fake: FakeBridge) -> list[str]:
    return [r["data"]["line"] for r in fake.records if r["kind"] == "tx" and r["src"] == "claude"]


def _claude_motion_tx(fake: FakeBridge) -> list[str]:
    return [x for x in _claude_tx(fake) if x and x not in ("$G", "$#", "?")]


async def test_unarmed_motion_is_refused_and_nothing_sent(sim_fake: FakeBridge, home: Path) -> None:
    async with _client(home) as client:
        for tool, args in [
            ("move", {"z": -5}),
            ("jog", {"axis": "Z", "distance": -1, "feed": 500}),
            ("probe", {"axis": "Z", "max_distance": 5, "feed": 100}),
            ("send", {"lines": ["G53 G0 Z-5"], "expect": "retract"}),
            ("set_work_offset", {"z": -50}),
        ]:
            text, err = await _call(client, tool, args)
            assert err, tool
            assert "needs Motion authority" in text, text
            assert "request_authority" in text
    assert _claude_tx(sim_fake) == []


async def test_locked_gives_plain_message(sim_fake: FakeBridge, home: Path) -> None:
    sim_fake.set_level("motion")
    sim_fake.set_locked(True)
    async with _client(home) as client:
        for tool, args in [
            ("move", {"z": -5}),
            ("probe", {"axis": "Z", "max_distance": 5, "feed": 100}),
            ("hold", {}),
            ("reset", {}),
            ("origins", {}),
            ("plan_resume", {"target": "stopped"}),
        ]:
            text, err = await _call(client, tool, args)
            assert err, tool
            assert text.endswith("UGS access is locked by the operator"), text
    assert not [r for r in sim_fake.records if r["kind"] == "tx"]


async def test_unhomed_then_home_with_operator_confirmation(
    sim_fake: FakeBridge, home: Path
) -> None:
    sim_fake.set_level("motion")
    async with _client(home) as client:
        text, err = await _call(client, "move", {"z": -5})
        assert err
        assert "hasn't been homed since UGS connected" in text
        assert "controller is in alarm" in text

        text, err = await _call(client, "send", {"lines": ["$H"], "expect": "machine homes"})
        assert not err, text
        assert "waiting for the operator's confirmation in UGS" in text
        assert not sim_fake.homed
        dialog = sim_fake.confirm_operator(kind="line")
        assert dialog.detail == "$H"
        assert sim_fake.wait_idle() is not None

        text, err = await _call(client, "status")
        assert "Homed this connection: yes" in text
        assert "State: Idle" in text
    cmd = [r["data"] for r in sim_fake.records if r["kind"] == "command" and r["src"] == "claude"]
    assert [c["state"] for c in cmd if c["line"] == "$H"] == [
        "queued",
        "awaiting_operator",
        "sent",
        "ok",
    ]


async def test_home_declined_by_operator(sim_fake: FakeBridge, home: Path) -> None:
    sim_fake.set_level("motion")
    async with _client(home) as client:
        _, err = await _call(client, "send", {"lines": ["$H"], "expect": "machine homes"})
        assert not err
    sim_fake.confirm_operator(accept=False)
    cid = next(c for c, r in sim_fake.commands.items() if r["lines"][0]["line"] == "$H")
    assert sim_fake.wait_result(cid)["state"] == "dropped"
    assert not sim_fake.homed


async def test_bitzero_z_probe_flow(sim_fake: FakeBridge, home: Path) -> None:
    """Home, go over the stock at safe Z, come down, probe the BitZero, set Z0, verify."""
    _armed_and_homed(sim_fake)
    async with _client(home) as client:
        text, err = await _call(client, "move", {**STOCK_XY, "z": -5})
        assert not err, text
        assert "Now: Idle, MPos X-300 Y-370 Z-5" in text
        assert "Assumptions: envelope X -445..0" in text  # unverified profile values are named

        text, err = await _call(client, "move", {"z": -35, "expect": "18 mm above the plate"})
        assert not err, text

        text, err = await _call(
            client,
            "probe",
            {"axis": "Z", "max_distance": 25, "feed": 100, "expect": "touch the BitZero"},
        )
        assert not err, text
        assert "[PRB:-300.000,-370.000,-42.600:1]" in text
        assert "Contact at machine X-300 Y-370 Z-42.6" in text
        assert "plate 13 mm, sourced, NOT measured; ledger B1" in text
        assert "set_work_offset(z=-55.6)" in text
        assert "MPos X-300 Y-370 Z-40.6" in text  # retracted 2 mm

        text, err = await _call(client, "set_work_offset", {"z": -55.6})
        assert not err, text
        assert "$# reads G54 X0 Y0 Z-55.6: verified." in text
        assert "WPos X-300 Y-370 Z15" in text  # tip 15 mm above the stock top: 13 + 2

    assert _claude_motion_tx(sim_fake) == [
        "G21G90G94G53G0X-300Y-370Z-5",
        "G21G90G94G53G0Z-35",
        "G21G91G38.2Z-25F100",
        "G0Z2",
        "G90",
        "G21G10L2P1Z-55.6",
    ]
    expects = {
        r["data"]["expect"]
        for r in sim_fake.records
        if r["kind"] == "command" and r["src"] == "claude"
    }
    assert {"touch the BitZero", "18 mm above the plate", "G54 = Z-55.6"} <= expects
    assert sim_fake.sim.modal.absolute  # G90 restored
    assert sim_fake.origins[-1]["wco"] == [0.0, 0.0, -55.6]
    assert [r["data"]["method"] for r in sim_fake.records if r["kind"] == "origin"] == ["g10"]


async def test_bitsetter_probe_and_tool_change_delta(sim_fake: FakeBridge, home: Path) -> None:
    _armed_and_homed(sim_fake)
    async with _client(home) as client:
        await _call(client, "move", {"x": -4, "y": -449, "z": -5})
        text, err = await _call(client, "move", {"z": -45})
        assert not err, text
        text, err = await _call(client, "probe", {"axis": "Z", "max_distance": 25, "feed": 200})
        assert not err, text
        assert f"Z{BITSETTER_CONTACT_Z:g}" in text.split("Contact at machine")[1]
        assert "This is the BitSetter position (profile X-4 Y-449, assumed; ledger B6)" in text
        assert "BitZero" not in text

        # The operator installs a 12.5 mm longer tool: its tip now rests on the button.
        sim_fake.sim.swap_tool(52.5)
        text, err = await _call(client, "probe", {"axis": "Z", "max_distance": 10, "feed": 50})
        assert err
        assert "probe input is already active (Pn:P)" in text
        text, err = await _call(client, "move", {"z": -40})
        assert not err, text
        text, err = await _call(client, "probe", {"axis": "Z", "max_distance": 25, "feed": 50})
        assert not err, text
        assert "Contact at machine X-4 Y-449 Z-52.5" in text  # 12.5 mm higher


async def test_refusals_through_mcp(sim_fake: FakeBridge, home: Path) -> None:
    _armed_and_homed(sim_fake)
    async with _client(home) as client:
        await _call(client, "move", {**STOCK_XY, "z": -5})
        await _call(client, "move", {"z": -35})
        before = len(_claude_motion_tx(sim_fake))
        cases: list[tuple[str, dict[str, Any], str]] = [
            ("send", {"lines": ["M6 T21"], "expect": "tool change"}, "tool changes are confirmed"),
            ("send", {"lines": ["G91 G0 Z5"], "expect": "up 5"}, "End the lines with 'G90'"),
            ("send", {"lines": ["$130=845"], "expect": "fix travel"}, "never changes $ settings"),
            ("send", {"lines": ["M3 S12000"], "expect": "spin"}, "the operator starts the router"),
            ("send", {"lines": ["S12000"], "expect": "speed"}, "the operator starts the router"),
            ("send", {"lines": ["G53 G0 Z-5"], "expect": ""}, "expected outcome"),
            ("move", {"x": -500}, "outside the profile envelope X -445..0"),
            ("move", {"z": 2}, "outside the profile envelope Z -95..0"),
            ("move", {"x": -100}, "below the safe height -5"),
            ("probe", {"axis": "Z", "max_distance": 30, "feed": 100}, "exceeds the 25 mm limit"),
            ("probe", {"axis": "X-", "max_distance": 10, "feed": 100}, "ledger B3"),
            ("probe", {"axis": "X", "max_distance": 10, "feed": 100}, "must be Z (down)"),
            ("jog", {"axis": "X", "distance": 150, "feed": 500}, "at most 100 mm"),
            ("jog", {"axis": "Y", "distance": -90, "feed": 1000}, "outside the profile envelope"),
            ("send", {"lines": ["$H", "G0 Z1"], "expect": "x"}, "must be sent on its own"),
        ]
        for tool, args, needle in cases:
            text, err = await _call(client, tool, args)
            assert err, (tool, args, text)
            assert needle in text, (tool, args, text)
            assert "Traceback" not in text
        sim_fake.sim.manual_probe_contact = True
        text, err = await _call(client, "probe", {"axis": "Z", "max_distance": 5, "feed": 100})
        assert err
        assert "Pn:P" in text
        sim_fake.sim.manual_probe_contact = False
    assert len(_claude_motion_tx(sim_fake)) == before


async def test_probe_miss_resets_modal_and_disarms(sim_fake: FakeBridge, home: Path) -> None:
    _armed_and_homed(sim_fake)
    async with _client(home) as client:
        await _call(client, "move", {"x": -100, "y": -100, "z": -5})  # not over the stock
        text, err = await _call(client, "probe", {"axis": "Z", "max_distance": 5, "feed": 100})
        assert not err, text
        assert "ALARM:5" in text
        assert "No contact within 5 mm" in text
        assert "sent a soft reset" in text
        assert "Claude's authority is now query" in text  # alarm withdraws Motion (SPEC §4.3)
        assert sim_fake.sim.modal.absolute
        text, err = await _call(client, "move", {"z": -5})
        assert err
        assert "needs Motion authority" in text


async def test_error_mid_call_restores_g90(
    home: Path, validate: Callable[[str, Any], None]
) -> None:
    # G20 followed by G21 passes the checker (the call doesn't end in inches), but the simulator
    # doesn't model inch mode and answers error:20 after the G91 line ran.
    prof = json.loads(json.dumps(local_profile()[0]))
    with FakeBridge(home, sim=_sim(), profile=prof) as fake:
        _armed_and_homed(fake)
        async with _client(home) as client:
            await _call(client, "move", {**STOCK_XY, "z": -5})
            await _call(client, "move", {"z": -35})
            text, err = await _call(
                client,
                "send",
                {"lines": ["G91 G0 Z1", "G20", "G21", "G90"], "expect": "inch round trip"},
            )
            assert not err, text
            assert "error:20" in text
            assert "-> dropped" in text
            assert "I sent 'G21 G90 G94 G17' to restore it" in text
        assert fake.sim.modal.absolute
        assert _claude_motion_tx(fake)[-1] == "G21G90G94G17"
    for rec in fake.records:
        validate("log-record", rec)


async def test_jog_and_send(sim_fake: FakeBridge, home: Path) -> None:
    _armed_and_homed(sim_fake)
    async with _client(home) as client:
        text, err = await _call(client, "jog", {"axis": "X", "distance": -20, "feed": 2000})
        assert not err, text
        assert "MPos X-21 Y-1 Z-1" in text
        text, err = await _call(
            client,
            "send",
            {"lines": ["G53 G0 Z-5", "G91 G0 Y-10", "G90", "$G"], "expect": "down then back"},
        )
        assert not err, text
        assert "MPos X-21 Y-11 Z-5" in text
        assert "[GC:G0 G54 G17 G21 G90" in text
        # D21: Claude may switch the router off
        text, err = await _call(client, "send", {"lines": ["M5"], "expect": "router stops"})
        assert not err, text
        assert _claude_motion_tx(sim_fake)[-1] == "M5"


async def test_hold_resume_reset(sim_fake: FakeBridge, home: Path) -> None:
    _armed_and_homed(sim_fake)
    client_http = BridgeClient(home)
    sim_fake.sim.time_scale = 1.0
    acc = await client_http.command(["G53 G1 X-200 F600"], tag="slow", expect="20 s move")
    assert (await client_http.wait_command(str(acc["id"]), timeout=5))["state"] == "done"
    async with _client(home) as client:
        text, err = await _call(client, "hold")
        assert not err, text
        assert text.startswith("Feed hold sent (0x21)")
        assert "Now: Hold" in text
        text, err = await _call(client, "move", {"z": -5})
        assert err
        assert "not Idle: use resume to continue" in text
        text, err = await _call(client, "resume")
        assert not err, text
        assert "Now: Run" in text
        text, err = await _call(client, "reset")
        assert not err, text
        assert "Soft reset sent (0x18)" in text
        assert "Now: Alarm" in text  # reset while moving: ALARM:3, position lost
        assert "Claude's authority is now query" in text
    assert not sim_fake.homed
    assert any(r["kind"] == "event" and r["data"].get("code") == 3 for r in sim_fake.records)


async def test_origins_and_apply_origin(sim_fake: FakeBridge, home: Path) -> None:
    _armed_and_homed(sim_fake)
    async with _client(home) as client:
        text, err = await _call(client, "origins")
        assert text == "No origins recorded yet."
        await _call(client, "set_work_offset", {"x": -280, "y": -360, "z": -55.6})
        await _call(client, "set_work_offset", {"z": -50})
        text, err = await _call(client, "origins")
        assert not err
        assert "2 origin(s); current WCO X-280 Y-360 Z-50" in text
        assert "o-1: G54 X-280 Y-360 Z-55.6, g10" in text
        assert "(differs by X0 Y0 Z-5.6)" in text
        assert "o-2: G54 X-280 Y-360 Z-50, g10" in text
        assert "(= current)" in text

        text, err = await _call(client, "apply_origin", {"origin_id": "o-1"})
        assert not err, text
        assert "$# reads G54 X-280 Y-360 Z-55.6: verified." in text
        text, err = await _call(client, "apply_origin", {"origin_id": "o-9"})
        assert err
        assert "no origin 'o-9' (known: o-1, o-2)" in text
    restored = [r["data"] for r in sim_fake.records if r["kind"] == "origin"]
    assert restored[-1] == {
        "wcs": "G54",
        "wco": [-280.0, -360.0, -55.6],
        "method": "restored",
        "origin_id": "o-1",
    }


JOURNAL = {
    "format": "ugs-bridge-job-state/1",
    "updated": "2026-11-15T18:02:11.120-05:00",
    "file": {"path": "/jobs/dr_ryan.nc", "sha256": "ab" * 32, "lines": 26781},
    "stream": "stopped",
    "line_acked": 14210,
    "line_done": 14188,
    "last_mpos": [-212.4, -301.0, -58.1],
    "operation": "OP 05 Pocket",
    "tool": "T21",
    "wco": [0.0, 0.0, 0.0],
    "bitsetter_ref": -7.52,
    "origin_id": "o-1",
    "modal": {"units": "G21", "distance": "G90", "plane": "G17", "wcs": "G54"},
}


def _resume_plan_validator() -> Draft202012Validator:
    schema = json.loads((SCHEMA_DIR / "job-state.schema.json").read_text())
    return Draft202012Validator(
        {"$schema": schema["$schema"], "$defs": schema["$defs"], "$ref": "#/$defs/ResumePlan"}
    )


async def test_job_state_plan_and_start_resume(
    sim_fake: FakeBridge, home: Path, validate: Callable[[str, Any], None]
) -> None:
    validate("job-state", JOURNAL)
    async with _client(home) as client:
        text, err = await _call(client, "job_state")
        assert text.startswith("No job journal yet")
        sim_fake.job_state = JOURNAL
        text, err = await _call(client, "job_state")
        assert not err
        assert "last line acknowledged 14210, last known done 14188" in text

        text, err = await _call(client, "plan_resume", {"target": "stopped"})
        assert not err, text
        assert "Resume plan p-1 for {'stopped': True}: start at line 14188 (a safe point)" in text
        assert "Requires: home, tool_measure." in text
        assert "[NO ] homed: not homed" in text
        assert "  G21 G53 G0 Z-5" in text
        assert "  G0 X-212.400 Y-301.000" in text  # back to where the journal stopped
        assert "my check: line 3" not in text
        # The live WCO is X0 Y0 Z0 (nothing probed yet), so the clearance move is above Z0.
        assert "my check: line 4 'G0 Z5': this move could reach machine Z 5 mm" in text
        assert "start_resume('p-1')" in text

        # Without a last position the canned plan returns to X10 Y20 (work), which with
        # WCO X0 Y0 is outside the envelope: the advisory check of the preamble says so.
        sim_fake.job_state = {k: v for k, v in JOURNAL.items() if k != "last_mpos"}
        text, err = await _call(client, "plan_resume", {"target": "OP 05"})
        assert not err
        assert "{'operation': 5}" in text
        assert "my check: line 3 'G0 X10.000 Y20.000': this move could reach machine X 10" in text

        text, err = await _call(client, "start_resume", {"plan_id": "p-1"})
        assert err
        assert "needs Stream authority (granted: query)" in text
        sim_fake.set_level("stream")
        text, err = await _call(client, "start_resume", {"plan_id": "p-1"})
        assert not err, text
        assert "waiting for the operator's confirmation in UGS" in text
    sim_fake.confirm_operator(kind="resume")
    v = _resume_plan_validator()
    for plan in sim_fake.plans.values():
        errors = list(v.iter_errors(plan))
        assert not errors, errors[0].message
    events = [r["data"]["event"] for r in sim_fake.records if r["kind"] == "job"]
    assert events == ["resume_planned", "resume_planned", "resume_started"]


async def test_run_file_needs_stream_and_operator(
    sim_fake: FakeBridge, home: Path, tmp_path: Path
) -> None:
    nc = tmp_path / "square.nc"
    nc.write_text("G21 G90\nG53 G0 Z-5\nG53 G0 X-20 Y-20\nG53 G0 X-1 Y-1\n")
    _armed_and_homed(sim_fake)
    async with _client(home) as client:
        text, err = await _call(client, "run_file", {"path": str(nc)})
        assert err
        assert "needs Stream authority (granted: motion)" in text
        sim_fake.set_level("stream")
        text, err = await _call(client, "run_file", {"path": str(nc)})
        assert not err, text
        assert "Loaded square.nc (4 lines" in text
        assert "Waiting for the operator to confirm the run in UGS" in text
        assert sim_fake.stream["state"] == "idle"
        dialog = sim_fake.confirm_operator(kind="file_run")
        assert "square.nc" in dialog.detail
        cid = max(c for c in sim_fake.commands if c.startswith("s-"))
        assert sim_fake.wait_result(cid)["state"] == "done"
        text, err = await _call(client, "status")
        assert "Stream: completed" in text
    actions = [r["data"]["action"] for r in sim_fake.records if r["kind"] == "file"]
    assert actions == ["loaded", "stream_start", "stream_end"]


async def test_fake_authority_levels_and_lock_drop(sim_fake: FakeBridge, home: Path) -> None:
    http = BridgeClient(home)
    with pytest.raises(BridgeForbiddenError) as exc:
        await http.apply_origin("o-1")
    assert exc.value.required == "motion"
    with pytest.raises(BridgeForbiddenError) as exc:
        await http.command(["G0 X1"])
    assert exc.value.required == "motion"
    sim_fake.set_level("motion")
    with pytest.raises(BridgeForbiddenError) as exc:
        await http.file_run()
    assert exc.value.required == "stream"
    with pytest.raises(BridgeForbiddenError) as exc:
        await http.resume("p-1")
    assert exc.value.required == "stream"
    # Locking drops a command that waits for the operator.
    acc = await http.command(["$H"], expect="home")
    assert sim_fake.pending_dialogs("line")
    sim_fake.set_locked(True)
    assert sim_fake.wait_result(str(acc["id"]))["state"] == "dropped"
    assert sim_fake.level.value == "query"

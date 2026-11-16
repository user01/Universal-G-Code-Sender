"""BitZero V2 XY/XYZ probing and feed-hold deceleration, over a real loopback TCP socket.

Geometry under test (``ugs_sim.bitzero``): stock top -95.6 (tool-tip machine Z), tool 40 mm
long, block thickness from the profile (ledger B1), 58 mm square (B2), placeholder bore 22 mm with
its center 14.5 mm in from the registration corner (B3 unknown), placeholder lip 2 mm.
"""

from __future__ import annotations

import json
import re
import time

import pytest

from ugs_sim.profile import load_profile
from ugs_sim.server import ThreadedSimServer
from ugs_sim.testing import GrblClient, bitzero_xyz_corner

PRB = re.compile(r"\[PRB:([-\d.]+),([-\d.]+),([-\d.]+):([01])\]")
STOCK_TOP = -95.6
TOOL_LEN = 40.0
_PLATE = load_profile().bitzero_plate_thickness
assert _PLATE is not None
THICKNESS: float = _PLATE  # the profile value (ledger B1)
TOP_SPINDLE = STOCK_TOP + THICKNESS + TOOL_LEN
BORE_DIA, BORE_OFF = 22.0, 14.5
LIP = 2.0
CORNER = (-420.0, -420.0)  # the stock's front-left corner


def bitzero(sim: ThreadedSimServer, command: str) -> dict[str, object]:
    reply = sim.control(command)
    assert reply.startswith("ok bitzero "), reply
    doc = json.loads(reply[len("ok bitzero ") :])
    assert isinstance(doc, dict)
    return doc


def prb(lines: list[str]) -> tuple[float, float, float, int]:
    m = next(m for m in map(PRB.match, lines) if m)
    return (float(m.group(1)), float(m.group(2)), float(m.group(3)), int(m.group(4)))


def goto(c: GrblClient, x: float, y: float, z: float) -> None:
    """Retract, travel, descend (machine coordinates), wait until there."""
    assert c.command("G53 G0 Z-10")[-1] == "ok"
    assert c.command(f"G53 G0 X{x:.3f} Y{y:.3f}")[-1] == "ok"
    assert c.command(f"G53 G0 Z{z:.3f}")[-1] == "ok"
    c.wait_state("Idle")


def in_bore(c: GrblClient, center: tuple[float, float], dx: float = 0.0, dy: float = 0.0) -> None:
    goto(c, center[0] + dx, center[1] + dy, TOP_SPINDLE - 5.0)


# ------------------------------------------------------------------ geometry


def test_place_reports_geometry(sim: ThreadedSimServer) -> None:
    info = bitzero(sim, "bitzero")
    assert info["mode"] == "follow"
    assert info["bore_placeholder"] is True  # profile has bore_diameter: null (ledger B3)
    info = bitzero(sim, "bitzero place")
    assert info["mode"] == "placed"
    assert info["corner"] == list(CORNER)
    assert info["top_z"] == pytest.approx(STOCK_TOP + THICKNESS)
    assert info["bore_center"] == [CORNER[0] + BORE_OFF, CORNER[1] + BORE_OFF]
    assert info["square"] == [-422.0, -422.0, -364.0, -364.0]
    info = bitzero(sim, "bitzero place -300 -380")
    assert info["corner"] == [-300.0, -380.0]
    info = bitzero(sim, "bitzero bore 20 16 17")
    assert (info["bore_diameter"], info["bore_offset"]) == (20.0, [16.0, 17.0])
    assert info["bore_placeholder"] is False
    assert bitzero(sim, "bitzero follow")["mode"] == "follow"
    assert sim.control("tooldia 3.175") == "ok tool_diameter_mm=3.175"
    assert sim.control("tooldia") == "ok tool_diameter_mm=3.175"


@pytest.mark.parametrize(
    "command",
    ["bitzero place -400", "bitzero bore 22", "bitzero bore 30 10", "tooldia -1", "tooldia x"],
)
def test_bitzero_control_errors(sim: ThreadedSimServer, command: str) -> None:
    assert sim.control(command).startswith("error: ")


def test_z_probe_on_placed_block(homed: GrblClient, sim: ThreadedSimServer) -> None:
    bitzero(sim, "bitzero place")
    goto(homed, -390.0, -390.0, -30.0)  # over solid aluminum, beside the bore
    x, y, z, ok = prb(homed.command("G91 G38.2 Z-20 F200"))
    assert (x, y, ok) == (-390.0, -390.0, 1)
    assert z == pytest.approx(TOP_SPINDLE, abs=1e-3)
    assert "|Pn:P" in homed.status()
    homed.command("G90")
    # Over the bore the tool falls through to the (non-conductive) stock: no contact.
    goto(homed, -405.5, -405.5, -30.0)
    out = homed.command("G91 G38.2 Z-20 F200")
    assert out[-1] == "ALARM:5"
    assert prb(out)[3] == 0
    homed.command("$X")
    homed.command("G90")
    # Away from the block, nothing is under the tool any more (no follow-the-tool plate).
    goto(homed, -300.0, -370.0, -30.0)
    assert homed.command("G91 G38.3 Z-20 F200")[-1] == "ok"
    assert homed.command("$#")[-2].endswith(":0]")


def test_z_probe_tool_overhanging_block_edge(homed: GrblClient, sim: ThreadedSimServer) -> None:
    bitzero(sim, "bitzero place")
    sim.control("tooldia 12")
    # Center 5 mm beyond the right face (-364): the 6 mm radius still overlaps the block.
    goto(homed, -359.0, -390.0, -30.0)
    assert prb(homed.command("G91 G38.2 Z-20 F200"))[2] == pytest.approx(TOP_SPINDLE, abs=1e-3)
    homed.command("G90")
    goto(homed, -357.0, -390.0, -30.0)  # 7 mm beyond: clear
    assert homed.command("G91 G38.3 Z-20 F200")[-1] == "ok"


# ------------------------------------------------------------- XY probing


@pytest.mark.parametrize("tool", [1.0, 3.175, 6.35, 12.7])
def test_xy_probe_bore_wall(homed: GrblClient, sim: ThreadedSimServer, tool: float) -> None:
    bitzero(sim, "bitzero place")
    sim.control(f"tooldia {tool}")
    center = (CORNER[0] + BORE_OFF, CORNER[1] + BORE_OFF)
    in_bore(homed, center)
    reach = BORE_DIA / 2 - tool / 2
    for axis, sign, idx in (("X", -1, 0), ("X", 1, 0), ("Y", -1, 1), ("Y", 1, 1)):
        hit = prb(homed.command(f"G91 G38.2 {axis}{sign * 15} F100"))
        assert hit[3] == 1
        assert hit[idx] == pytest.approx(center[idx] + sign * reach, abs=1e-3)
        assert hit[2] == pytest.approx(TOP_SPINDLE - 5.0)
        assert "|Pn:P" in homed.status()
        assert homed.mpos()[idx] == pytest.approx(hit[idx], abs=1e-3)  # stopped at contact
        homed.command("G90")
        in_bore(homed, center)


def test_xy_probe_diagonal_hits_circle(homed: GrblClient, sim: ThreadedSimServer) -> None:
    bitzero(sim, "bitzero place")
    center = (CORNER[0] + BORE_OFF, CORNER[1] + BORE_OFF)
    in_bore(homed, center)
    x, y, _, ok = prb(homed.command("G91 G38.2 X10 Y10 F100"))
    assert ok == 1
    assert ((x - center[0]) ** 2 + (y - center[1]) ** 2) ** 0.5 == pytest.approx(
        BORE_DIA / 2 - 6.35 / 2, abs=1e-3
    )


@pytest.mark.parametrize("tool", [3.175, 6.35])
def test_xy_probe_outer_faces(homed: GrblClient, sim: ThreadedSimServer, tool: float) -> None:
    """Edge probing from outside: the contact depends on the tool radius."""
    bitzero(sim, "bitzero place")
    sim.control(f"tooldia {tool}")
    r = tool / 2
    left_face = CORNER[0] - LIP
    goto(homed, left_face - 10.0, -390.0, TOP_SPINDLE - 3.0)  # beside the left face
    assert prb(homed.command("G91 G38.2 X15 F100"))[0] == pytest.approx(left_face - r, abs=1e-3)
    homed.command("G90")
    front_face = CORNER[1] - LIP
    goto(homed, -390.0, front_face - 10.0, TOP_SPINDLE - 3.0)
    assert prb(homed.command("G91 G38.2 Y15 F100"))[1] == pytest.approx(front_face - r, abs=1e-3)
    homed.command("G90")
    # Above the block top the tool passes over it.
    goto(homed, left_face - 10.0, -390.0, TOP_SPINDLE + 1.0)
    assert homed.command("G91 G38.3 X15 F100")[-1] == "ok"


def test_xy_probe_failure(homed: GrblClient, sim: ThreadedSimServer) -> None:
    bitzero(sim, "bitzero place")
    center = (CORNER[0] + BORE_OFF, CORNER[1] + BORE_OFF)
    in_bore(homed, center)
    out = homed.command("G91 G38.2 X-2 F100")  # wall is 7.8 mm away
    assert out[-1] == "ALARM:5"
    assert prb(out)[3] == 0
    assert homed.command("$X")[-1] == "ok"
    out = homed.command("G91 G38.3 X2 F100")
    assert out[-1] == "ok"
    assert prb(out)[3] == 0
    # Removed block: nothing to touch.
    homed.command("G90")
    in_bore(homed, center)
    sim.control("bitzero off")
    assert prb(homed.command("G91 G38.3 X-15 F100"))[3] == 0


def test_probe_away(homed: GrblClient, sim: ThreadedSimServer) -> None:
    bitzero(sim, "bitzero place")
    center = (CORNER[0] + BORE_OFF, CORNER[1] + BORE_OFF)
    in_bore(homed, center)
    wall = prb(homed.command("G91 G38.2 X-15 F100"))[0]
    assert "|Pn:P" in homed.status()
    # G38.4: move away until the circuit opens; reported where contact was lost.
    x, _, _, ok = prb(homed.command("G91 G38.4 X3 F50"))
    assert ok == 1
    assert x == pytest.approx(wall, abs=1e-3)
    assert "Pn:" not in homed.status()
    # G38.4 needs contact at the start: ALARM:4.
    assert homed.command("G91 G38.4 X3 F50")[-1] == "ALARM:4"
    homed.command("$X")
    # Held probe input never opens: G38.5 completes with :0, G38.4 alarms.
    sim.control("probe on")
    out = homed.command("G91 G38.5 X1 F50")
    assert (out[-1], prb(out)[3]) == ("ok", 0)
    out = homed.command("G91 G38.4 X1 F50")
    assert out[-1] == "ALARM:5"


def test_legacy_follow_plate_still_z_only_semantics(
    homed: GrblClient, sim: ThreadedSimServer
) -> None:
    """Without ``bitzero place`` the plate follows the tool over the stock, as before."""
    goto(homed, -300.0, -370.0, -30.0)
    z = prb(homed.command("G91 G38.2 Z-20 F200"))[2]
    assert z == pytest.approx(TOP_SPINDLE, abs=1e-3)
    homed.command("G90")
    # BitSetter (a point switch): an XY move over it below its top triggers.
    goto(homed, -20.0, -448.2, -66.0)  # tip at -106: 1 mm below the setter top (-105)
    # the button sits at X-4.2 (profile, ledger B6) with a 4 mm radius
    assert prb(homed.command("G91 G38.2 X20 F200"))[0] == pytest.approx(-8.2, abs=1e-3)


# ------------------------------------------------------- XYZ corner routine


@pytest.mark.parametrize("tool", [1.0, 3.175, 6.35, 12.7])
@pytest.mark.parametrize(
    ("corner", "start_error"),
    [(None, (1.3, -0.8)), ((-400.0, -410.0), (-2.0, 1.5)), ((-300.0, -380.0), (0.0, 0.0))],
)
def test_bitzero_xyz_corner_recovers_stock_corner(
    homed: GrblClient,
    sim: ThreadedSimServer,
    tool: float,
    corner: tuple[float, float] | None,
    start_error: tuple[float, float],
) -> None:
    info = bitzero(sim, "bitzero place" + (f" {corner[0]} {corner[1]}" if corner else ""))
    true_corner = corner or CORNER
    sim.control(f"tooldia {tool}")
    cx, cy = info["bore_center"]  # type: ignore[misc]
    # The operator jogs roughly over the bore, tip 5 mm above the block.
    goto(homed, cx + start_error[0], cy + start_error[1], TOP_SPINDLE + 5.0)
    result = bitzero_xyz_corner(
        homed,
        thickness=THICKNESS,
        bore_diameter=BORE_DIA,
        bore_offset=(BORE_OFF, BORE_OFF),
        tool_diameter=tool,
    )
    assert result.corner[0] == pytest.approx(true_corner[0], abs=0.01)
    assert result.corner[1] == pytest.approx(true_corner[1], abs=0.01)
    assert result.z0_spindle == pytest.approx(STOCK_TOP + TOOL_LEN, abs=0.01)
    g54 = next(s for s in homed.command("$#") if s.startswith("[G54:"))
    gx, gy, gz = (float(v) for v in g54[5:-1].split(","))
    assert (gx, gy, gz) == pytest.approx((*true_corner, STOCK_TOP + TOOL_LEN), abs=0.01)


def test_xyz_corner_with_wrong_offset_is_off_by_the_difference(
    homed: GrblClient, sim: ThreadedSimServer
) -> None:
    """Why ledger B3 matters: the routine trusts the configured offset."""
    bitzero(sim, "bitzero place")
    bitzero(sim, "bitzero bore 22 15.5")  # the "real" block (unknown to the routine)
    goto(homed, CORNER[0] + 15.5, CORNER[1] + 15.5, TOP_SPINDLE + 5.0)
    result = bitzero_xyz_corner(
        homed, thickness=THICKNESS, bore_diameter=22.0, bore_offset=(14.5, 14.5), tool_diameter=6.35
    )
    assert result.corner == pytest.approx((CORNER[0] + 1.0, CORNER[1] + 1.0), abs=0.01)


# --------------------------------------------------------- feed-hold decel


@pytest.mark.parametrize(
    ("move", "speed", "accel", "axis"),
    [("X-100 F3000", 50.0, 400.0, 0), ("Z-60 F1500", 25.0, 200.0, 2)],
)
def test_feed_hold_decelerates(
    homed: GrblClient,
    sim: ThreadedSimServer,
    move: str,
    speed: float,
    accel: float,
    axis: int,
) -> None:
    sim.control("timescale 0.5")  # stretch time so Hold:1 is observable
    homed.command(f"G53 G1 {move}")
    homed.wait_state("Run")
    t0 = time.monotonic()
    homed.send(b"!?")  # same write: the status is the hold's first instant
    first = homed.read_until(lambda s: s.startswith("<"))[-1]
    assert first.startswith("<Hold:1|")
    assert f"|FS:{speed * 60:.0f}," in first  # still at full speed
    start = float(first.split("MPos:")[1].split("|")[0].split(",")[axis])
    held = homed.wait_state("Hold:0")
    elapsed = (time.monotonic() - t0) * 0.5  # simulated seconds
    stop = float(held.split("MPos:")[1].split("|")[0].split(",")[axis])
    assert abs(stop - start) == pytest.approx(speed**2 / (2 * accel), abs=2e-3)
    assert speed / accel <= elapsed < speed / accel + 0.25  # + polling and tick latency
    assert "|FS:0," in held


def test_resume_ignored_until_hold_complete(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 0.1")  # 0.125 s of decel = 1.25 s real
    homed.command("G53 G1 X-100 F3000")
    homed.wait_state("Run")
    homed.send(b"!")
    assert homed.status().startswith("<Hold:1|")
    homed.send(b"~!")  # resume during decel: ignored; a second hold: no restart
    assert homed.status().startswith("<Hold:1|")
    sim.control("timescale 2")
    homed.wait_state("Hold:0")
    homed.send(b"~")
    assert homed.status().startswith("<Run|")


def test_reset_during_decel_raises_alarm_3(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 0.1")
    homed.command("G53 G1 X-100 F3000")
    homed.wait_state("Run")
    homed.send(b"!")
    assert homed.status().startswith("<Hold:1|")
    homed.send(b"\x18")  # still moving: GRBL loses position
    lines = homed.read_until(lambda s: s.startswith("[MSG:'$H'"))
    assert "ALARM:3" in lines
    assert sim.call(lambda m: m.homed) is False


def test_reset_after_decel_keeps_position(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 0.5")
    homed.command("G53 G1 X-100 F3000")
    homed.wait_state("Run")
    homed.send(b"!")
    held = homed.wait_state("Hold:0")
    homed.send(b"\x18")
    assert homed.readline() == "Grbl 1.1f ['$' for help]"
    st = homed.status()
    assert st.startswith("<Idle|")
    assert st.split("|")[1] == held.split("|")[1]
    assert sim.call(lambda m: m.homed) is True

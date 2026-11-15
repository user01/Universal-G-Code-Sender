"""GRBL behavior of the simulator, over a real loopback TCP socket."""

from __future__ import annotations

import json
import re
import socket
import time

import pytest

from ugs_sim.machine import ShapeokoGrbl
from ugs_sim.profile import load_profile
from ugs_sim.server import ThreadedSimServer
from ugs_sim.testing import GrblClient

BANNER = "Grbl 1.1f ['$' for help]"
UNLOCK = "[MSG:'$H'|'$X' to unlock]"
PRB = re.compile(r"\[PRB:([-\d.]+),([-\d.]+),([-\d.]+):([01])\]")


def state(sim: ThreadedSimServer) -> dict[str, object]:
    reply = sim.control("state")
    assert reply.startswith("ok ")
    doc = json.loads(reply[3:])
    assert isinstance(doc, dict)
    return doc


def probe_bitsetter(c: GrblClient) -> float:
    """Profile-style BitSetter measurement: park over it at Z-5, probe down, return PRB Z."""
    assert c.command("G53 G0 Z-5")[-1] == "ok"
    assert c.command("G53 G0 X-4 Y-449")[-1] == "ok"
    out = c.command("G91 G38.2 Z-90 F800")
    assert out[-1] == "ok", out
    m = PRB.match(out[0])
    assert m, out
    assert m.group(4) == "1", out
    assert c.command("G90")[-1] == "ok"
    return float(m.group(3))


# --------------------------------------------------------------------- connect


def test_connect_banner_and_alarm_lock(sim: ThreadedSimServer) -> None:
    c = GrblClient(sim.port)
    assert c.readline() == BANNER
    assert c.readline() == UNLOCK
    st = c.status()
    assert st.startswith("<Alarm|MPos:0.000,0.000,0.000|")
    assert c.command("G0 X-10") == ["error:9"]
    assert c.command("") == ["ok"]  # UGS's responsiveness probe in alarm
    c.close()


def test_initial_queries_ugs_sends(client: GrblClient) -> None:
    ver = client.command("$I")
    assert ver[0].startswith("[VER:1.1f.")
    assert ver[1] == "[OPT:V,15,128]"
    settings = client.command("$$")
    assert "$130=445.000" in settings
    assert "$131=456.000" in settings
    assert "$132=95.000" in settings
    assert "$22=1" in settings
    gc = client.command("$G")
    assert gc[0].startswith("[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0")
    assert client.command("$N")[-1] == "ok"


def test_unlock_without_homing(client: GrblClient) -> None:
    assert client.command("$X") == ["[MSG:Caution: Unlocked]", "ok"]
    assert client.status().startswith("<Idle|")


def test_homing(client: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 1")
    client.send("$H\n")
    assert client.wait_state("Home").startswith("<Home|")
    client.read_until(lambda s: s == "ok", timeout=10)
    assert client.mpos() == (-1.0, -1.0, -1.0)  # $27 pull-off from the back-right-top corner


def test_jog_and_soft_limits(homed: GrblClient) -> None:
    assert homed.command("$J=G91 G21 X-10 Y-20 F5000") == ["ok"]
    homed.wait_state("Idle")
    assert homed.mpos() == (-11.0, -21.0, -1.0)
    assert homed.command("$J=G91 X50 F5000") == ["error:15"]  # beyond X0
    assert homed.command("$J=G53 X-500 F5000") == ["error:15"]  # beyond -445


def test_soft_limit_gcode_is_critical(homed: GrblClient) -> None:
    out = homed.command("G53 G0 X-450")
    assert out[-1] == "ALARM:2"
    assert homed.readline() == "[MSG:Reset to continue]"
    homed.send("$X\n")  # ignored until reset, no reply
    assert homed.status().startswith("<Alarm|")
    homed.send(b"\x18")
    assert homed.readline() == BANNER
    assert homed.readline() == UNLOCK
    assert homed.command("$X")[-1] == "ok"


# ------------------------------------------------------------ status + probing


def test_bitsetter_touch_sets_probe_pin(homed: GrblClient, sim: ThreadedSimServer) -> None:
    assert "Pn:" not in homed.status()
    z = probe_bitsetter(homed)
    # Contact where the tool tip (40 mm below the spindle reference) meets the setter top.
    assert z == pytest.approx(-105.0 + 40.0)
    st = homed.status()
    assert "|Pn:P" in st
    assert homed.mpos() == (-4.0, -449.0, z)
    assert homed.command("G91 G0 Z2")[-1] == "ok"
    homed.wait_state("Idle")
    assert "Pn:" not in homed.status()
    assert state(sim)["probe"] is False


def test_probe_reports_prb_and_dollar_hash(homed: GrblClient) -> None:
    z = probe_bitsetter(homed)
    lines = homed.command("$#")
    assert f"[PRB:-4.000,-449.000,{z:.3f}:1]" in lines


def test_probe_failure_alarm(homed: GrblClient) -> None:
    # Over empty table, 10 mm of travel touches nothing.
    assert homed.command("G53 G0 X-200 Y-200")[-1] == "ok"
    out = homed.command("G91 G38.2 Z-10 F500")
    assert out[0].startswith("[PRB:")
    assert out[0].endswith(":0]")
    assert out[-1] == "ALARM:5"
    homed.command("$X")
    out = homed.command("G91 G38.3 Z-10 F500")  # G38.3: no error on miss
    assert out[-1] == "ok"
    assert out[0].endswith(":0]")


def test_probe_already_triggered_alarm(homed: GrblClient, sim: ThreadedSimServer) -> None:
    assert sim.control("probe on") == "ok probe on"
    assert homed.status().endswith("|Pn:P>") or "|Pn:P" in homed.status()
    assert homed.command("G91 G38.2 Z-10 F500")[-1] == "ALARM:4"


def test_tool_change_shifts_bitsetter_trigger(homed: GrblClient, sim: ThreadedSimServer) -> None:
    s_a = probe_bitsetter(homed)
    assert sim.control("tool 52.5") == "ok tool_length_mm=52.5"
    s_b = probe_bitsetter(homed)
    assert s_b - s_a == pytest.approx(12.5)
    sim.control("tool 31")
    assert probe_bitsetter(homed) - s_a == pytest.approx(-9.0)


def test_bitzero_on_stock(homed: GrblClient, sim: ThreadedSimServer) -> None:
    assert homed.command("G53 G0 X-300 Y-370")[-1] == "ok"  # over the stock
    out = homed.command("G91 G38.2 Z-60 F200")
    m = PRB.match(out[0])
    assert m
    assert m.group(4) == "1"
    # stock top -95.6 + plate 12.7 (placeholder) + tool 40
    assert float(m.group(3)) == pytest.approx(-95.6 + 12.7 + 40.0, abs=1e-3)
    sim.control("bitzero off")
    homed.command("G0 Z5")
    assert homed.command("G91 G38.3 Z-20 F200")[0].endswith(":0]")


def test_g10_l2_sets_wco(homed: GrblClient) -> None:
    assert homed.command("G10 L2 P1 X-400 Y-410 Z-55.6") == ["ok"]
    lines = homed.command("$#")
    assert "[G54:-400.000,-410.000,-55.600]" in lines
    assert "|WCO:-400.000,-410.000,-55.600" in homed.status()
    assert homed.command("G10 L20 P1 Z0")[-1] == "ok"  # zero Z at the current position
    assert "[G54:-400.000,-410.000,-1.000]" in homed.command("$#")


# ------------------------------------------------------------------ streaming


def zigzag(n: int) -> list[str]:
    lines = ["G21 G90 G94", "G10 L2 P1 X-400 Y-400 Z-50", "G0 Z5", "G0 X0 Y0", "G1 Z-1 F600"]
    for i in range(n):
        lines.append(f"G1 X{(i % 2) * 100:.3f} Y{i * 0.2:.3f} F2000 (pass {i})")
    lines += ["G0 Z5", "M5", "M2"]
    return lines


def test_streaming_char_counting(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 1e6")
    lines = zigzag(600)
    replies = homed.stream(lines)
    assert replies == ["ok"] * len(lines)
    homed.wait_state("Idle")
    snap = state(sim)
    assert snap["rx_overflow"] is False
    assert snap["lines_received"] == len(lines) + 1  # + $H
    assert homed.mpos() == pytest.approx((-400.0 + 100.0, -400.0 + 599 * 0.2, -45.0))


def test_overflow_is_recorded(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 0.001")  # nothing finishes: the planner fills, then RX
    for _ in range(40):
        homed.send("G1 X-10 Y-10 Z-2 F100\nG1 X-20 Y-20 Z-3 F100\n")
    time.sleep(0.2)
    assert state(sim)["rx_overflow"] is True


def test_feed_hold_and_resume(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 1")
    assert homed.command("G53 G1 X-100 F3000") == ["ok"]  # 2 s
    homed.wait_state("Run")
    homed.send(b"!")
    assert homed.status().startswith("<Hold:1|")
    held = homed.status()
    assert held.startswith("<Hold:0|")
    time.sleep(0.1)
    assert homed.status().split("|")[1] == held.split("|")[1]  # not moving
    homed.send(b"~")
    assert homed.status().startswith("<Run|")
    sim.control("timescale 1000")
    homed.wait_state("Idle")
    assert homed.mpos()[0] == -100.0


def test_jog_cancel(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 1")
    homed.command("$J=G91 X-200 F1000")
    homed.wait_state("Jog")
    homed.send(b"\x85")
    st = homed.wait_state("Idle")
    assert -200.0 < homed.mpos()[0] < -1.0, st


def test_arcs_tools_and_modal_words(homed: GrblClient) -> None:
    for line in ["T2", "G17 G21 G40 G49 G80 G90 G94 G54", "M8", "G0 X-10 Y-10", "M9"]:
        assert homed.command(line) == ["ok"], line
    assert homed.command("G2 X-20 Y-10 I-5 J0 F1000") == ["ok"]
    assert homed.command("X-30 Y-10 R5") == ["ok"]  # modal arc continues
    assert homed.command("M6") == ["error:20"]  # stock GRBL 1.1 has no M6
    gc = homed.command("$G")[0]
    assert " T2 " in gc
    homed.wait_state("Idle")
    assert homed.mpos()[:2] == (-30.0, -10.0)


def test_overrides_and_door(homed: GrblClient, sim: ThreadedSimServer) -> None:
    homed.send(bytes([0x91, 0x91, 0x9A]))
    assert "|Ov:120,100,110" in homed.status()
    sim.control("timescale 1")
    homed.command("G53 G1 X-100 F3000")
    homed.wait_state("Run")
    assert sim.control("door").startswith("ok door Door:1")
    assert homed.status().startswith("<Door:1|")
    homed.send(b"~")  # door still open: ignored
    assert homed.status().startswith("<Door:1|")
    sim.control("door close")
    assert homed.status().startswith("<Door:0|")
    homed.send(b"~")
    assert homed.status().startswith("<Run|")


def test_injected_alarm(homed: GrblClient, sim: ThreadedSimServer) -> None:
    assert sim.control("alarm 1") == "ok alarm 1"
    assert homed.readline() == "ALARM:1"
    assert homed.readline() == "[MSG:Reset to continue]"


# ------------------------------------------------------------------- resets


def poll_until_dropped(c: GrblClient) -> None:
    for _ in range(100):
        c.status(timeout=0.5)


def test_soft_reset_idle_keeps_position(homed: GrblClient, sim: ThreadedSimServer) -> None:
    homed.command("G10 L2 P1 X-100 Y-100 Z-30")
    homed.command("G91 G0 X-10")
    homed.wait_state("Idle")
    homed.send(b"\x18")
    assert homed.readline() == BANNER
    st = homed.status()
    assert st.startswith("<Idle|MPos:-11.000,-1.000,-1.000|")  # no alarm, position kept
    assert homed.command("$G")[0].startswith("[GC:G0 G54 G17 G21 G90 ")  # modal reset to G90
    assert "[G54:-100.000,-100.000,-30.000]" in homed.command("$#")  # offsets persist
    assert state(sim)["homed"] is True


def test_soft_reset_during_motion_raises_alarm_3(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 1")
    homed.command("G53 G1 X-100 F3000")
    homed.wait_state("Run")
    homed.send(b"\x18")
    lines = homed.read_until(lambda s: s == UNLOCK)
    assert lines[-3:] == ["ALARM:3", BANNER, UNLOCK]
    x = homed.mpos()[0]
    assert -100.0 < x < -1.0  # stopped where it was, position kept but flagged lost
    assert homed.status().startswith("<Alarm|")
    assert state(sim)["homed"] is False


def test_reset_after_completed_hold_keeps_position(
    homed: GrblClient, sim: ThreadedSimServer
) -> None:
    sim.control("timescale 1")
    homed.command("G53 G1 X-100 F3000")
    homed.wait_state("Run")
    homed.send(b"!")
    homed.wait_state("Hold:0")
    homed.send(b"\x18")
    assert homed.readline() == BANNER
    assert homed.status().startswith("<Idle|")


def test_reconnect_is_a_hardware_reset(sim: ThreadedSimServer) -> None:
    c = GrblClient(sim.port)
    c.banner()
    c.command("$H", timeout=10)
    c.command("G10 L2 P1 X-50 Y-60 Z-20")
    c.command("G53 G0 X-4 Y-449")
    c.wait_state("Idle")
    c.close()
    time.sleep(0.05)

    c = GrblClient(sim.port)
    assert c.banner() == [BANNER, UNLOCK]
    assert c.status().startswith("<Alarm|MPos:0.000,0.000,0.000|")
    assert "[G54:-50.000,-60.000,-20.000]" in c.command("$#")  # EEPROM keeps offsets
    snap = state(sim)
    assert snap["physical"] == [-4.0, -449.0, -1.0]
    assert snap["homed"] is False
    # Unlocked without homing, the gantry is physically over the BitSetter even though
    # it reports 0,0,0: probing still hits the setter, at a Z shifted by the lost offset.
    c.command("$X")
    out = c.command("G91 G38.2 Z-90 F800")
    assert out[0] == "[PRB:0.000,0.000,-64.000:1]"
    c.command("$H", timeout=10)
    assert state(sim)["physical"] == [-1.0, -1.0, -1.0]
    c.close()


def test_unplug_keeps_motion_running(homed: GrblClient, sim: ThreadedSimServer) -> None:
    sim.control("timescale 1")
    homed.command("G53 G1 X-100 F3000")
    homed.wait_state("Run")
    assert sim.control("unplug") == "ok unplugged"
    with pytest.raises((ConnectionError, TimeoutError, OSError)):
        poll_until_dropped(homed)
    sim.control("timescale 1000")
    time.sleep(0.1)
    snap = state(sim)
    assert snap["client_connected"] is False
    assert snap["physical"] == [-100.0, -1.0, -1.0]


def test_one_client_at_a_time(client: GrblClient, sim: ThreadedSimServer) -> None:
    second = socket.create_connection(("127.0.0.1", sim.port), timeout=2)
    assert second.recv(100) == b""  # closed straight away
    second.close()
    assert client.status().startswith("<Alarm|")


def test_profile_without_connect_reset() -> None:
    from dataclasses import replace

    profile = replace(load_profile(), connect_resets_controller=False)
    with ThreadedSimServer(ShapeokoGrbl(profile, time_scale=100)) as sim:
        c = GrblClient(sim.port)
        assert c.status().startswith("<Alarm|")  # power-on lock, but no banner on connect
        assert c.log == [c.log[-1]]
        c.close()


def test_control_errors(sim: ThreadedSimServer) -> None:
    assert sim.control("bogus").startswith("error: unknown command")
    assert sim.control("tool").startswith("ok tool_length_mm=40")
    assert sim.control("alarm").startswith("error: alarm")
    assert sim.control("probe maybe").startswith("error: probe")


def test_control_over_tcp(sim: ThreadedSimServer) -> None:
    assert sim.control_port
    with socket.create_connection(("127.0.0.1", sim.control_port), timeout=2) as s:
        s.sendall(b"tool 45\nstate\n")
        f = s.makefile("r")
        assert f.readline().strip() == "ok tool_length_mm=45"
        assert json.loads(f.readline()[3:])["tool_length_mm"] == 45.0

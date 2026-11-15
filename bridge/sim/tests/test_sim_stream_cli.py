"""Streaming a real job file, and the ``ugs-sim`` command line."""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from pathlib import Path

import pytest

from ugs_sim.machine import ShapeokoGrbl
from ugs_sim.profile import load_profile
from ugs_sim.server import ThreadedSimServer
from ugs_sim.testing import GrblClient

TESTDATA_NC = Path(__file__).resolve().parents[2] / "testdata" / "nc"
BIG_FILE = TESTDATA_NC / "dr_ryan_complete_T02_then_T21.nc"
_COMMENT = re.compile(r"\([^)]*\)|;.*$")


def clean(path: Path) -> list[str]:
    """What UGS's default preprocessors send: comments and blank lines removed."""
    out = []
    for raw in path.read_text().splitlines():
        line = _COMMENT.sub("", raw).strip()
        if line:
            out.append(line)
    return out


@pytest.mark.skipif(not BIG_FILE.is_file(), reason="bridge/testdata/nc not present (gitignored)")
def test_stream_26k_line_job_file() -> None:
    lines = clean(BIG_FILE)
    assert len(lines) > 20_000
    # M6 ignored here: in UGS the tool-change interceptor removes it before it reaches GRBL.
    machine = ShapeokoGrbl(load_profile(), time_scale=float("inf"), m6="ignore")
    with ThreadedSimServer(machine) as sim:
        c = GrblClient(sim.port)
        c.banner()
        assert c.command("$H", timeout=10)[-1] == "ok"
        # Stock top at the tool tip: X0 Y0 = front-left of the stock, Z0 = raw top.
        assert c.command("G10 L2 P1 X-420 Y-420 Z-55.6") == ["ok"]
        start = time.monotonic()
        replies = c.stream(lines, timeout=120)
        elapsed = time.monotonic() - start
        assert replies == ["ok"] * len(lines)
        c.wait_state("Idle")
        snap = sim.call(lambda m: m.snapshot())
        assert snap["rx_overflow"] is False
        assert snap["state"] == "Idle"
        c.close()
    print(f"streamed {len(lines)} lines in {elapsed:.1f} s")


def test_cli_prints_ports_and_serves() -> None:
    proc = subprocess.Popen(
        [sys.executable, "-m", "ugs_sim.server", "--port", "0", "--time-scale", "100"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        assert proc.stdout is not None
        listening = proc.stdout.readline().strip()
        control = proc.stdout.readline().strip()
        m = re.fullmatch(r"LISTENING 127\.0\.0\.1:(\d+)", listening)
        assert m, listening
        assert re.fullmatch(r"CONTROL 127\.0\.0\.1:\d+", control), control
        c = GrblClient(int(m.group(1)))
        assert c.banner()[0].startswith("Grbl 1.1f")
        assert c.command("$X")[-1] == "ok"
        c.close()
    finally:
        proc.terminate()
        proc.wait(5)
    assert proc.returncode == 0


def test_cli_refuses_non_loopback() -> None:
    result = subprocess.run(
        [sys.executable, "-m", "ugs_sim.server", "--host", "0.0.0.0"],
        capture_output=True,
        text=True,
        timeout=10,
    )
    assert result.returncode != 0
    assert "loopback only" in result.stderr


@pytest.mark.timeout(600)
@pytest.mark.skipif(
    os.environ.get("UGS_SIM_JAVA") != "1", reason="set UGS_SIM_JAVA=1 (needs JDK 25 + ~/.m2)"
)
def test_ugs_grbl_controller_drives_sim() -> None:
    """ugs-core's GrblController over the TCP driver: connect, home, jog, probe, stream."""
    script = Path(__file__).resolve().parents[1] / "scripts" / "ugs-compat.sh"
    result = subprocess.run([str(script)], capture_output=True, text=True, timeout=600)
    assert result.returncode == 0, result.stdout[-4000:] + result.stderr[-2000:]
    assert "UGS-SIM CHECK PASSED" in result.stdout

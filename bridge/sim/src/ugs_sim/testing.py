"""A minimal GRBL host for tests and scripts (not a sender for real machines)."""

from __future__ import annotations

import re
import socket
import time
from collections.abc import Callable
from dataclasses import dataclass

_MPOS = re.compile(r"MPos:([-\d.]+),([-\d.]+),([-\d.]+)")
_PRB = re.compile(r"\[PRB:([-\d.]+),([-\d.]+),([-\d.]+):([01])\]")


class GrblClient:
    """Minimal GRBL host over TCP for tests: line commands, status polls, char counting."""

    def __init__(self, port: int) -> None:
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self._buf = b""
        self.log: list[str] = []

    def close(self) -> None:
        self.sock.close()

    def send(self, data: bytes | str) -> None:
        self.sock.sendall(data.encode() if isinstance(data, str) else data)

    def readline(self, timeout: float = 5.0) -> str:
        deadline = time.monotonic() + timeout
        while b"\n" not in self._buf:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"no line from simulator; seen {self.log[-10:]}")
            self.sock.settimeout(remaining)
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("simulator closed the connection")
            self._buf += chunk
        line, _, self._buf = self._buf.partition(b"\n")
        text = line.decode().rstrip("\r")
        self.log.append(text)
        return text

    def read_until(self, pred: Callable[[str], bool], timeout: float = 5.0) -> list[str]:
        lines: list[str] = []
        deadline = time.monotonic() + timeout
        while True:
            line = self.readline(max(0.01, deadline - time.monotonic()))
            lines.append(line)
            if pred(line):
                return lines

    def command(self, line: str, timeout: float = 5.0) -> list[str]:
        """Send one line; return the responses up to and including ok/error/ALARM."""
        self.send(line + "\n")
        out = self.read_until(
            lambda s: s == "ok" or s.startswith(("error:", "ALARM:")), timeout=timeout
        )
        return [s for s in out if not s.startswith("<")]

    def status(self, timeout: float = 5.0) -> str:
        self.send(b"?")
        return self.read_until(lambda s: s.startswith("<"), timeout=timeout)[-1]

    def mpos(self) -> tuple[float, float, float]:
        m = _MPOS.search(self.status())
        assert m, "no MPos in status"
        return (float(m.group(1)), float(m.group(2)), float(m.group(3)))

    def wait_state(self, prefix: str, timeout: float = 10.0) -> str:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            st = self.status()
            if st[1:].startswith(prefix):
                return st
            time.sleep(0.01)
        raise TimeoutError(f"state {prefix!r} not reached; last {self.log[-1:]}")

    def banner(self) -> list[str]:
        """Read the reset banner and, when homing is enabled, the unlock message."""
        return self.read_until(lambda s: s.startswith("[MSG:'$H'"))

    def stream(self, lines: list[str], rx_size: int = 128, timeout: float = 60.0) -> list[str]:
        """Character-counting streaming (UGS's GrblCommunicator does the same)."""
        pending: list[int] = []
        replies: list[str] = []
        i = 0
        deadline = time.monotonic() + timeout
        while i < len(lines) or pending:
            while i < len(lines) and sum(pending) + len(lines[i]) + 1 <= rx_size:
                self.send(lines[i] + "\n")
                pending.append(len(lines[i]) + 1)
                i += 1
            line = self.readline(max(0.01, deadline - time.monotonic()))
            if line == "ok" or line.startswith("error:"):
                replies.append(line)
                pending.pop(0)
            elif line.startswith("ALARM:"):
                raise AssertionError(f"alarm while streaming line {i}: {line}")
        return replies


# ------------------------------------------------- reference BitZero XYZ routine


class ProbeError(AssertionError):
    pass


def probe(c: GrblClient, axis: str, distance: float, feed: float) -> tuple[float, float, float]:
    """One relative ``G38.2`` move; returns the machine position of the contact."""
    out = c.command(f"G91 G38.2 {axis}{distance:.3f} F{feed:g}")
    c.command("G90")
    m = next((_PRB.match(s) for s in out if _PRB.match(s)), None)
    if out[-1] != "ok" or m is None or m.group(4) != "1":
        raise ProbeError(f"probe {axis}{distance} failed: {out}")
    return (float(m.group(1)), float(m.group(2)), float(m.group(3)))


def probe_two_pass(
    c: GrblClient, axis: str, distance: float, *, fast: float = 200, slow: float = 50
) -> float:
    """Fast probe, back off 1 mm, slow probe (SPEC 11.4); returns the slow contact on ``axis``."""
    probe(c, axis, distance, fast)
    sign = 1.0 if distance > 0 else -1.0
    c.command(f"G91 G0 {axis}{-sign:.3f}")
    c.command("G90")
    hit = probe(c, axis, sign * 2.0, slow)
    return hit["XYZ".index(axis)]


@dataclass(frozen=True)
class CornerResult:
    top_spindle_z: float
    bore_center: tuple[float, float]
    corner: tuple[float, float]
    z0_spindle: float
    """G54 Z (machine) that makes work Z0 the stock top under this tool."""


def bitzero_xyz_corner(
    c: GrblClient,
    *,
    thickness: float,
    bore_diameter: float,
    bore_offset: tuple[float, float],
    tool_diameter: float,
    set_g54: bool = True,
) -> CornerResult:
    """Reference BitZero V2 corner probe, starting with the tool roughly over the bore center
    and its tip a few mm above the block top (where the operator jogs it).

    1. Z: step sideways onto the aluminum, probe down onto the block top.
    2. X then Y: lower into the bore and probe both walls of each axis; the midpoint of a
       chord is the bore center whatever the tool diameter.
    3. Stock corner = bore center - offset; Z0 = block top - thickness.
    """
    radius, r = bore_diameter / 2.0, tool_diameter / 2.0
    sx, sy, _ = c.mpos()
    side = radius + r + 3.0  # far enough to land the whole tool on the aluminum
    c.command(f"G53 G0 X{sx + side:.3f}")
    top = probe_two_pass(c, "Z", -15.0)
    c.command("G91 G0 Z3")
    c.command(f"G90 G53 G0 X{sx:.3f} Y{sy:.3f}")
    c.command(f"G53 G1 Z{top - 5.0:.3f} F500")  # 5 mm into the bore
    reach = radius + 2.0
    x1 = probe_two_pass(c, "X", -reach)
    c.command(f"G53 G0 X{sx:.3f}")
    x2 = probe_two_pass(c, "X", reach)
    cx = (x1 + x2) / 2.0
    c.command(f"G53 G0 X{cx:.3f}")
    y1 = probe_two_pass(c, "Y", -reach)
    c.command(f"G53 G0 Y{sy:.3f}")
    y2 = probe_two_pass(c, "Y", reach)
    cy = (y1 + y2) / 2.0
    c.command(f"G53 G0 Y{cy:.3f}")
    c.command(f"G53 G0 Z{top + 5.0:.3f}")
    corner = (cx - bore_offset[0], cy - bore_offset[1])
    z0 = top - thickness
    if set_g54:
        c.command(f"G10 L2 P1 X{corner[0]:.3f} Y{corner[1]:.3f} Z{z0:.3f}")
    c.wait_state("Idle")
    return CornerResult(top, (cx, cy), corner, z0)

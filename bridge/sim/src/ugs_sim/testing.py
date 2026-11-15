"""A minimal GRBL host for tests and scripts (not a sender for real machines)."""

from __future__ import annotations

import re
import socket
import time
from collections.abc import Callable

_MPOS = re.compile(r"MPos:([-\d.]+),([-\d.]+),([-\d.]+)")


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

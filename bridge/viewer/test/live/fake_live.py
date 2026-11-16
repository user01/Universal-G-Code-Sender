"""Live-viewer test bench: the fake bridge (ugs_mcp.testing.fake_bridge) driven like UGS would be.

Starts a FakeBridge on 127.0.0.1 (ephemeral port, temp UGS_BRIDGE_HOME), with a viewer token and
the viewer's files served at /view/, and a simulated GRBL (resurfacer's SimulatedGrbl) whose world
matches the Shapeoko profile. It never opens a serial port and never starts UGS.

Then it reads one command per line on stdin and answers with one JSON line on stdout:

    setup                       home, rough work offset, load the program
    jog X Y Z                   G53 G0 to a machine position
    probe                       BitZero Z probe over the stock, then set Z0 (origin record)
    stream FROM TO              stream file lines FROM..TO (first call: a resume-style preamble);
                                an M6 runs the tool-change interceptor and waits for `confirm`
    confirm                     the operator confirms the bit swap (answered by the stream)
    speed N                     simulator time scale
    alarm / unlock              soft-limit alarm (G53 G0 X100), then $X
    lock / unlock-mcp           operator lock for Claude (the viewer is not affected)
    refuse on|off               refuse viewer WebSockets (to show the viewer's locked state)
    disconnect / reconnect      UGS loses / regains the controller
    stop / start                the bridge goes away / comes back on the same port
    status                      current fake state
    quit

Run from bridge/:  uv run python viewer/test/live/fake_live.py [--program PATH] [--home DIR]
"""

from __future__ import annotations

import argparse
import contextlib
import json
import queue
import re
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

from shapeoko_surface.controller.grbl_parser import parse_status
from shapeoko_surface.controller.simulator import PLACEHOLDER_SETTINGS, SimulatedGrbl, SimWorld
from shapeoko_surface.core.geometry import Point2, Rect
from starlette.websockets import WebSocket

from ugs_mcp.testing.fake_bridge import FakeBridge

HERE = Path(__file__).resolve().parent
VIEWER = HERE.parent.parent
BRIDGE = VIEWER.parent
VT = "live-test-viewer-token"
STOCK_FRONT_LEFT = (-425.0, -436.0)  # machine XY of work X0 Y0
STOCK_SIZE = (240.0, 95.0, 19.4)
SPOILBOARD_Z = -85.0  # machine Z of the spoilboard (tool tip)
PARK = (-222.0, -440.0, -5.0)
BITSETTER = (-4.0, -449.0)


class Bench(FakeBridge):
    refuse_viewers = False

    async def ws_events(self, ws: WebSocket) -> None:
        if self.refuse_viewers and "vt" in ws.query_params:
            # the form a browser can see (api.md: accept, then close at once with 4423)
            await ws.accept()
            await ws.close(code=4423, reason="locked_by_operator")
            return
        await super().ws_events(ws)


def out(**kw: Any) -> None:
    sys.stdout.write(json.dumps(kw) + "\n")
    sys.stdout.flush()


class Driver:
    def __init__(self, fake: Bench, program: Path) -> None:
        self.fake = fake
        self.program = program
        self.lines = program.read_text().splitlines()
        self.next_line = 0
        self.ucmd = 0
        self.confirm = threading.Event()
        self.jobs: queue.Queue[tuple[str, list[str]]] = queue.Queue()
        self.alive = True
        self.tool = "T02"
        self.tool_len = {"T02": 30.0, "T21": 24.0}
        self.ref: float | None = None

    # --- wire helpers (all on the worker thread, under the fake's lock) ---------------------

    def handle(self, line: str, cmd_id: str | None, src: str, replies: list[str]) -> None:
        if line.startswith("<"):
            self.status_line(line)
            return
        data: dict[str, Any] = {"line": line}
        if cmd_id:
            data["cmd_id"] = cmd_id
        self.fake.emit("rx", src if cmd_id else "controller", data)
        if (m := re.match(r"^ALARM:(\d+)", line)) is not None:
            self.fake.last_alarm = int(m.group(1))
            self.fake.emit("event", "controller", {"type": "alarm", "code": int(m.group(1))})
        if (m := re.match(r"^\[PRB:([-\d.]+),([-\d.]+),([-\d.]+):([01])\]", line)) is not None:
            pos = [float(m.group(i)) for i in (1, 2, 3)]
            self.fake.emit(
                "event",
                "ugs",
                {"type": "probe", "position": pos, "units": "mm", "coordinates": "machine"},
            )
        replies.append(line)

    def status_line(self, raw: str) -> None:
        f = self.fake
        rep = parse_status(raw)
        if rep.wco is not None:
            f._wco = rep.wco
        st = f._controller_status(rep, raw)
        key = (st["state"], tuple(st["mpos"] or ()), st["pins"]["raw"], tuple(st["wco"] or ()))
        if key != f._last_status:
            f._last_status = key
            f.emit("status", "ugs", st)

    def poll(self) -> None:
        if self.fake.connected:
            self.fake.sim.write(b"?")

    def send(
        self, line: str, src: str = "ugs", file_line: int | None = None, timeout: float = 30.0
    ) -> list[str]:
        f = self.fake
        self.ucmd += 1
        cmd_id = f"u-{self.ucmd}"
        tx: dict[str, Any] = {"line": line, "cmd_id": cmd_id}
        if file_line is not None:
            tx["file_line"] = file_line
        with f._lock:
            f.emit("tx", src, tx)
            f.sim.write((line + "\n").encode("ascii"))
        replies: list[str] = []
        deadline = time.monotonic() + timeout
        last_poll = 0.0
        while time.monotonic() < deadline:
            with f._lock:
                now = time.monotonic()
                if now - last_poll > 0.08:
                    self.poll()
                    last_poll = now
                r = f.sim.readline(0.02)
                if r is None:
                    continue
                self.handle(r, None if r.startswith("<") else cmd_id, src, replies)
            if replies and (replies[-1] == "ok" or replies[-1].startswith(("error:", "ALARM:"))):
                break
        return replies

    def wait_idle(self, timeout: float = 60.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.fake._lock:
                self.poll()
                r = self.fake.sim.readline(0.05)
                if r is not None:
                    self.handle(r, None, "controller", [])
            if self.fake.sim.state == "Idle" and not self.fake.sim.planner:
                with self.fake._lock:
                    self.poll()
                    while (r := self.fake.sim.readline(0.02)) is not None:
                        self.handle(r, None, "controller", [])
                return
            time.sleep(0.03)

    def idle_poll(self) -> None:
        with self.fake._lock:
            if not self.fake.connected or self.fake._server is None:
                return
            self.poll()
            while (r := self.fake.sim.readline(0.01)) is not None:
                self.handle(r, None, "controller", [])

    # --- scenario steps -----------------------------------------------------------------------

    def setup(self) -> dict[str, Any]:
        self.send("$H", src="operator", timeout=120)
        self.fake.homed = True
        x0, y0 = STOCK_FRONT_LEFT
        top = SPOILBOARD_Z + STOCK_SIZE[2] + self.tool_len[self.tool]  # spindle Z at the stock top
        self.send(
            f"G10 L2 P1 X{x0} Y{y0} Z{top - 0.5:.3f}", src="operator"
        )  # rough Z, probed later
        info = self.fake.load_file(self.program)
        self.send("G53 G0 Z-5", src="operator")
        self.send(f"G53 G0 X{x0 + 120} Y{y0 + 47}", src="operator")
        self.wait_idle()
        return {"file": info}

    def jog(self, x: float, y: float, z: float) -> dict[str, Any]:
        self.send("G53 G0 Z-5", src="operator")
        self.send(f"G53 G0 X{x} Y{y}", src="operator")
        self.send(f"G53 G0 Z{z}", src="operator")
        self.wait_idle()
        return {}

    def probe(self) -> dict[str, Any]:
        plate = 13.0
        self.send("G53 G0 Z-5", src="claude")
        self.send(f"G53 G0 X{STOCK_FRONT_LEFT[0] + 30} Y{STOCK_FRONT_LEFT[1] + 30}", src="claude")
        self.wait_idle()
        r = self.send("G91 G38.2 Z-60 F300", src="claude", timeout=60)
        self.send("G90", src="claude")
        prb = next((x for x in r if x.startswith("[PRB:")), None)
        if prb is None:
            return {"error": r}
        self.send(f"G10 L20 P1 Z{plate}", src="claude")
        self.send("G91 G0 Z5", src="claude")
        self.send("G90", src="claude")
        self.wait_idle()
        wco = [round(v, 3) for v in self.fake._wco]
        self.fake.emit(
            "origin", "bridge", {"wcs": "G54", "wco": wco, "method": "bitzero_z", "tool": self.tool}
        )
        return {"prb": prb, "wco": wco}

    def _modal_before(self, n: int) -> tuple[list[float | None], float]:
        pos: list[float | None] = [None, None, None]
        feed = 500.0
        for raw in self.lines[: n - 1]:
            code = re.sub(r"\([^)]*\)|;.*$", "", raw).upper()
            if "G53" in code:
                continue
            for a, i in (("X", 0), ("Y", 1), ("Z", 2)):
                if (m := re.search(rf"{a}(-?[\d.]+)", code)) is not None:
                    pos[i] = float(m.group(1))
            if (m := re.search(r"F([\d.]+)", code)) is not None:
                feed = float(m.group(1))
        return pos, feed

    def stream(self, a: int, b: int) -> dict[str, Any]:
        f = self.fake
        if (
            self.next_line != a
        ):  # starting fresh at line a: stream_start, then a resume-style preamble
            assert f.file_info is not None
            info = f.file_info
            f.emit(
                "file",
                "ugs",
                {
                    "action": "stream_start",
                    "path": info["path"],
                    "sha256": info["sha256"],
                    "lines": info["lines"],
                },
            )
            f.emit("event", "ugs", {"type": "stream", "event": "started"})
            f.emit(
                "job",
                "bridge",
                {"event": "resume_started", "file_sha256": info["sha256"], "line": a},
            )
            pos, feed = self._modal_before(a)
            self.send("G21 G90 G94", src="bridge")
            self.send("G53 G0 Z-5", src="bridge")
            if pos[0] is not None and pos[1] is not None:
                self.send(f"G0 X{pos[0]} Y{pos[1]}", src="bridge")
            self.send("S18000 M3", src="bridge")
            if pos[2] is not None:
                self.send(f"G1 Z{pos[2]} F{feed}", src="bridge")
        for n in range(a, b + 1):
            raw = self.lines[n - 1]
            code = re.sub(r"\([^)]*\)|;.*$", "", raw).strip()
            if not code:
                continue
            if re.search(r"\bM0*6\b", code, re.I):
                self.tool_change(n, code)
                continue
            r = self.send(code, src="ugs", file_line=n)
            if r and r[-1].startswith(("error", "ALARM")):
                return {"stopped_at": n, "reply": r}
        self.next_line = b + 1
        self.wait_idle()
        return {"line": b}

    def tool_change(self, n: int, code: str) -> None:
        """The bridge's interceptor (SPEC §11.5, D10): park, swap, BitSetter, return."""
        f = self.fake
        m = re.search(r"T(\d+)", code)
        tool = f"T{int(m.group(1)):02d}" if m else self.tool
        self.wait_idle()
        before = list(f.sim.mpos)
        f.emit("tx", "ugs", {"line": code, "cmd_id": f"u-m6-{n}", "file_line": n})
        f.emit(
            "event",
            "bridge",
            {
                "type": "interceptor",
                "state": "active",
                "previous": "idle",
                "interceptor": "toolchange",
                "trigger": "M6",
                "file_line": n,
                "step": "park",
                "tool": tool,
            },
        )
        self.send("M5", src="bridge")
        self.send(f"G53 G0 Z{PARK[2]}", src="bridge")
        self.send(f"G53 G0 X{PARK[0]} Y{PARK[1]}", src="bridge")
        self.wait_idle()
        f.emit("tool", "bridge", {"event": "change_requested", "tool": tool})
        f.emit(
            "event",
            "bridge",
            {
                "type": "interceptor",
                "state": "active",
                "step": "swap",
                "tool": tool,
                "file_line": n,
            },
        )
        self.confirm.clear()
        out(event="waiting", for_="confirm", line=n, tool=tool)
        while not self.confirm.wait(0.1):
            self.idle_poll()
        f.emit("tool", "operator", {"event": "confirmed", "tool": tool})
        f.emit(
            "event",
            "bridge",
            {
                "type": "interceptor",
                "state": "active",
                "step": "measure",
                "tool": tool,
                "file_line": n,
            },
        )
        old_len = self.tool_len.get(self.tool, 30.0)
        new_len = self.tool_len.get(tool, 25.0)
        f.sim.swap_tool(new_len)
        self.send(f"G53 G0 X{BITSETTER[0]} Y{BITSETTER[1]}", src="bridge")
        r = self.send("G91 G38.2 Z-80 F800", src="bridge", timeout=60)
        self.send("G90", src="bridge")
        prb = next((x for x in r if x.startswith("[PRB:")), None)
        trigger = float(prb[5:].split(",")[2].split(":")[0]) if prb else None
        # tool-length compensation: shift WCO Z by the length difference (SPEC §11.4)
        wco = list(f._wco)
        wco[2] = round(wco[2] + (new_len - old_len), 3)
        self.send(f"G10 L2 P1 Z{wco[2]}", src="bridge")
        self.send(f"G53 G0 Z{PARK[2]}", src="bridge")
        f.emit(
            "tool",
            "bridge",
            {"event": "measured", "tool": tool, "trigger_mz": trigger, "wco_z": wco[2]},
        )
        f.emit("origin", "bridge", {"wcs": "G54", "wco": wco, "method": "g10", "tool": tool})
        f.emit(
            "event",
            "bridge",
            {
                "type": "interceptor",
                "state": "active",
                "step": "return",
                "tool": tool,
                "file_line": n,
            },
        )
        self.send(
            f"G53 G0 X{before[0]:.3f} Y{before[1]:.3f}", src="bridge"
        )  # D10: back to the pre-change XY at safe Z
        self.wait_idle()
        f.emit(
            "event",
            "bridge",
            {"type": "interceptor", "state": "idle", "previous": "active", "tool": tool},
        )
        self.tool = tool

    def alarm(self) -> dict[str, Any]:
        r = self.send("G53 G0 X100", src="claude")
        return {"reply": r}

    def unlock(self) -> dict[str, Any]:
        r = self.send("$X", src="operator")
        self.fake.last_alarm = None
        self.wait_idle(5)
        return {"reply": r}

    # --- loop --------------------------------------------------------------------------------

    def worker(self) -> None:
        while self.alive:
            try:
                cmd, args = self.jobs.get(timeout=0.1)
            except queue.Empty:
                with contextlib.suppress(Exception):  # a stopped server between polls
                    self.idle_poll()
                continue
            try:
                res = self.run(cmd, args)
                out(ok=True, cmd=cmd, **res)
            except Exception as e:  # report and keep serving
                out(ok=False, cmd=cmd, error=repr(e))

    def run(self, cmd: str, args: list[str]) -> dict[str, Any]:
        f = self.fake
        if cmd == "setup":
            return self.setup()
        if cmd == "jog":
            return self.jog(*(float(a) for a in args))
        if cmd == "probe":
            return self.probe()
        if cmd == "stream":
            return self.stream(int(args[0]), int(args[1]))
        if cmd == "speed":
            f.sim.time_scale = float(args[0])
            return {}
        if cmd == "alarm":
            return self.alarm()
        if cmd == "unlock":
            return self.unlock()
        if cmd == "lock":
            f.set_locked(True)
            return {}
        if cmd == "unlock-mcp":
            f.set_locked(False)
            return {}
        if cmd == "refuse":
            f.refuse_viewers = args[0] == "on"
            return {}
        if cmd == "disconnect":
            with f._lock:
                f.connected = False
                f.emit(
                    "session",
                    "ugs",
                    {
                        "event": "disconnect",
                        "port": "tcp://127.0.0.1:sim",
                        "reason": "connection_closed",
                    },
                )
            return {}
        if cmd == "reconnect":
            with f._lock:
                f.connected = True
                f.emit(
                    "session",
                    "ugs",
                    {
                        "event": "connect",
                        "port": "tcp://127.0.0.1:sim",
                        "driver": "TCP",
                        "firmware": "Grbl 1.1f",
                        "ugs_version": "fake",
                        "bridge_version": "fake",
                    },
                )
            return {}
        if cmd == "stop":
            f.stop()
            return {}
        if cmd == "start":
            f.start()
            return {"port": f.port}
        if cmd == "status":
            return {"seq": f.seq, "mpos": f.sim.mpos, "wco": list(f._wco), "state": f.sim.state}
        raise ValueError(f"unknown command {cmd}")


def main() -> None:
    ap = argparse.ArgumentParser()
    default = BRIDGE / "testdata/nc/dr_ryan_complete_T02_then_T21.nc"
    ap.add_argument(
        "--program",
        type=Path,
        default=default if default.exists() else BRIDGE / "testdata-public/sample-sign.nc",
    )
    ap.add_argument("--home", type=Path, default=None)
    ap.add_argument("--time-scale", type=float, default=10.0)
    ap.add_argument(
        "--cors-origin", action="append", default=[], help="dev page origin for ?live= mode"
    )
    a = ap.parse_args()
    home = a.home or Path(tempfile.mkdtemp(prefix="ugs-bridge-live-"))
    world = SimWorld(
        stock=Rect(
            STOCK_FRONT_LEFT[0],
            STOCK_FRONT_LEFT[1],
            STOCK_FRONT_LEFT[0] + STOCK_SIZE[0],
            STOCK_FRONT_LEFT[1] + STOCK_SIZE[1],
        ),
        stock_top_z=SPOILBOARD_Z + STOCK_SIZE[2],
        bitzero_plate_mm=13.0,
        bitsetter_xy=Point2(*BITSETTER),
        bitsetter_top_z=-80.0,
        tool_length_mm=30.0,
    )
    # travel as in the profile's envelope (the BitSetter sits at Y-449)
    settings = {**PLACEHOLDER_SETTINGS, 130: 445.0, 131: 456.0, 132: 95.0}
    sim = SimulatedGrbl(settings, world=world, time_scale=a.time_scale)
    profile = json.loads((BRIDGE / "profiles/shapeoko4-standard.json").read_text())
    fake = Bench(
        home,
        viewer_token=VT,
        viewer_dir=VIEWER,
        sim=sim,
        profile=profile,
        level="motion",
        cors_origins=a.cors_origin or None,
    )
    fake.start()
    drv = Driver(fake, a.program)
    t = threading.Thread(target=drv.worker, name="driver", daemon=True)
    t.start()
    out(
        ready=True,
        port=fake.port,
        vt=VT,
        home=str(home),
        program=str(a.program),
        view=f"http://127.0.0.1:{fake.port}/view/#vt={VT}",
    )
    try:
        for line in sys.stdin:
            parts = line.split()
            if not parts:
                continue
            if parts[0] == "quit":
                break
            if parts[0] == "confirm":
                drv.confirm.set()
                out(ok=True, cmd="confirm")
                continue
            drv.jobs.put((parts[0], parts[1:]))
    finally:
        drv.alive = False
        t.join(timeout=5)
        fake.stop()
        out(stopped=True)


if __name__ == "__main__":
    main()

"""GRBL simulator on a loopback TCP socket, for UGS's TCP connection driver.

    ugs-sim --host 127.0.0.1 --port 0 [--control-port 0] [--time-scale 50] [--tool 40]

prints ``LISTENING 127.0.0.1:<port>`` and ``CONTROL 127.0.0.1:<port>`` (one line each) once
both sockets are bound. In UGS choose the TCP driver and connect to ``127.0.0.1:<port>``.
See ``bridge/sim/README.md`` for the control channel.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import ipaddress
import json
import logging
import signal
import sys
import threading
from collections.abc import Callable
from concurrent.futures import Future
from pathlib import Path
from typing import TypeVar

from ugs_sim.machine import ShapeokoGrbl, WorldDefaults, settings_for, world_for
from ugs_sim.profile import load_profile

log = logging.getLogger("ugs_sim")
T = TypeVar("T")

CONTROL_HELP = (
    "commands: state | tool [<length_mm>] | tooldia [<mm>] | alarm <n> | door [open|close]"
    " | probe on|off | bitzero [on|off|place [<x> <y>]|follow|bore <dia> <offset> [<offset_y>]]"
    " | unplug | reset | timescale <x> | shutdown | help"
)


class SimServer:
    """One GRBL client at a time on ``port``; a line-based control channel on ``control_port``."""

    def __init__(
        self,
        machine: ShapeokoGrbl,
        *,
        host: str = "127.0.0.1",
        port: int = 0,
        control_port: int | None = 0,
        tick_s: float = 0.002,
    ) -> None:
        _require_loopback(host)
        self.machine = machine
        self.host = host
        self._port = port
        self._control_port = control_port
        self.tick_s = tick_s
        self.port = 0
        self.control_port: int | None = None
        self.connections = 0
        self._client: asyncio.StreamWriter | None = None
        self._servers: list[asyncio.Server] = []
        self._ticker: asyncio.Task[None] | None = None
        self._stopped = asyncio.Event()

    # ---------------------------------------------------------------- lifecycle

    async def start(self) -> None:
        srv = await asyncio.start_server(self._on_client, self.host, self._port)
        self._servers.append(srv)
        self.port = srv.sockets[0].getsockname()[1]
        if self._control_port is not None:
            ctl = await asyncio.start_server(self._on_control, self.host, self._control_port)
            self._servers.append(ctl)
            self.control_port = ctl.sockets[0].getsockname()[1]
        self._ticker = asyncio.create_task(self._tick_loop())

    async def wait_stopped(self) -> None:
        await self._stopped.wait()

    async def stop(self) -> None:
        self._stopped.set()
        if self._ticker is not None:
            self._ticker.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._ticker
        self.unplug()
        for srv in self._servers:
            srv.close()
        for srv in self._servers:
            await srv.wait_closed()
        self._servers.clear()

    @property
    def client_connected(self) -> bool:
        return self._client is not None

    def unplug(self) -> None:
        """Drop the GRBL client like a pulled USB cable; queued motion keeps running."""
        if self._client is not None:
            self._client.transport.abort()
            self._client = None

    # ---------------------------------------------------------------- GRBL port

    async def _tick_loop(self) -> None:
        while True:
            self.machine.tick()
            self._flush()
            await asyncio.sleep(self.tick_s)

    def _flush(self) -> None:
        out = self.machine.out
        writer = self._client
        if writer is None:
            out.clear()
            return
        chunks = []
        while out:
            line = out.popleft()
            log.debug("tx %s", line)
            chunks.append(line + "\r\n")
        if chunks:
            writer.write("".join(chunks).encode("ascii", errors="replace"))

    async def _on_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        if self._client is not None:
            log.warning("refusing a second client; one connection at a time")
            writer.close()
            return
        self._client = writer
        self.connections += 1
        log.info("client connected (%d)", self.connections)
        if self.machine.profile.connect_resets_controller:
            self.machine.power_cycle()  # USB DTR reset
        else:
            self.machine.out.clear()
        self._flush()
        try:
            while True:
                data = await reader.read(4096)
                if not data:
                    break
                log.debug("rx %r", data)
                self.machine.write(data)
                self._flush()
                await writer.drain()
        except (ConnectionError, OSError):
            pass
        finally:
            if self._client is writer:
                self._client = None
            writer.close()
            log.info("client disconnected")

    # ------------------------------------------------------------ control port

    async def _on_control(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while line := await reader.readline():
                reply = self.control(line.decode("utf-8", errors="replace"))
                writer.write((reply + "\n").encode())
                await writer.drain()
                if reply == "ok shutdown":
                    break
        except (ConnectionError, OSError):
            pass
        finally:
            writer.close()

    def control(self, command: str) -> str:
        """Execute one control command (on the event loop); returns a one-line reply."""
        parts = command.strip().split()
        if not parts:
            return "error: empty command"
        cmd, args = parts[0].lower(), parts[1:]
        m = self.machine
        try:
            if cmd == "help":
                return "ok " + CONTROL_HELP
            if cmd == "state":
                snap = m.snapshot()
                snap["client_connected"] = self.client_connected
                return "ok " + json.dumps(snap)
            if cmd == "tool":
                if args:
                    m.swap_tool(float(args[0]))
                return f"ok tool_length_mm={m.world.tool_length_mm:g}"
            if cmd == "alarm":
                m.inject_alarm(int(args[0]))
                self._flush()
                return f"ok alarm {int(args[0])}"
            if cmd == "door":
                if args and args[0] == "close":
                    m.close_door()
                else:
                    m.open_door()
                return f"ok door {m.state}"
            if cmd == "tooldia":
                if args:
                    m.set_tool_diameter(float(args[0]))
                return f"ok tool_diameter_mm={m.tool_diameter_mm:g}"
            if cmd == "bitzero" and (not args or args[0] in ("place", "follow", "bore")):
                return self._bitzero(args)
            if cmd in ("probe", "bitzero"):
                on = _on_off(args)
                if cmd == "probe":
                    m.manual_probe_contact = on
                else:
                    m.surface("bitzero").active = on
                return f"ok {cmd} {'on' if on else 'off'}"
            if cmd == "unplug":
                self.unplug()
                return "ok unplugged"
            if cmd == "reset":
                m.power_cycle()
                self._flush()
                return "ok reset"
            if cmd == "timescale":
                m.tick()  # account elapsed time at the old scale first
                m.time_scale = float(args[0])
                return f"ok timescale {m.time_scale:g}"
            if cmd == "shutdown":
                self._stopped.set()
                return "ok shutdown"
        except (IndexError, ValueError) as exc:
            return f"error: {cmd}: {exc or 'missing argument'}"
        return f"error: unknown command {cmd!r}; {CONTROL_HELP}"

    def _bitzero(self, args: list[str]) -> str:
        m = self.machine
        sub = args[0] if args else "info"
        if sub == "place":
            if len(args) not in (1, 3):
                raise ValueError("expected: bitzero place [<x> <y>]")
            corner = (float(args[1]), float(args[2])) if len(args) == 3 else None
            m.place_bitzero(corner)
        elif sub == "follow":
            m.follow_bitzero()
        elif sub == "bore":
            if len(args) not in (3, 4):
                raise ValueError("expected: bitzero bore <dia> <offset> [<offset_y>]")
            off_x = float(args[2])
            off_y = float(args[3]) if len(args) == 4 else off_x
            m.set_bitzero_bore(float(args[1]), (off_x, off_y))
        return "ok bitzero " + json.dumps(m.bitzero_info())


def _on_off(args: list[str]) -> bool:
    if not args or args[0].lower() not in ("on", "off"):
        raise ValueError("expected on|off")
    return args[0].lower() == "on"


def _require_loopback(host: str) -> None:
    if host == "localhost":
        return
    try:
        ok = ipaddress.ip_address(host).is_loopback
    except ValueError:
        ok = False
    if not ok:
        raise ValueError(f"ugs-sim binds loopback only, not {host!r}")


class ThreadedSimServer:
    """Run a :class:`SimServer` on its own event loop thread (tests, Python tools)."""

    def __init__(self, machine: ShapeokoGrbl | None = None, **kwargs: object) -> None:
        self.machine = machine or ShapeokoGrbl(load_profile())
        self._kwargs = kwargs
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, daemon=True)
        self.server: SimServer | None = None

    def __enter__(self) -> ThreadedSimServer:
        self.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.stop()

    def start(self) -> None:
        self._thread.start()

        async def make() -> SimServer:
            srv = SimServer(self.machine, **self._kwargs)  # type: ignore[arg-type]
            await srv.start()
            return srv

        self.server = asyncio.run_coroutine_threadsafe(make(), self._loop).result(10)

    @property
    def port(self) -> int:
        assert self.server is not None
        return self.server.port

    @property
    def control_port(self) -> int | None:
        assert self.server is not None
        return self.server.control_port

    def call(self, fn: Callable[[ShapeokoGrbl], T]) -> T:
        """Run ``fn(machine)`` on the server's loop thread and return its result."""
        fut: Future[T] = Future()

        def run() -> None:
            try:
                fut.set_result(fn(self.machine))
            except BaseException as exc:
                fut.set_exception(exc)

        self._loop.call_soon_threadsafe(run)
        return fut.result(10)

    def control(self, command: str) -> str:
        server = self.server
        assert server is not None
        return self.call(lambda _m: server.control(command))

    def stop(self) -> None:
        if self.server is not None:
            asyncio.run_coroutine_threadsafe(self.server.stop(), self._loop).result(10)
            self.server = None
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(10)
        self._loop.close()


# --------------------------------------------------------------------- CLI


def _parse_setting(text: str) -> tuple[int, float]:
    key, _, value = text.partition("=")
    try:
        return int(key.lstrip("$")), float(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"expected N=VALUE, got {text!r}") from None


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="ugs-sim", description=__doc__.split("\n\n")[0])
    p.add_argument("--host", default="127.0.0.1", help="loopback address only")
    p.add_argument("--port", type=int, default=0, help="GRBL port (0 = ephemeral)")
    p.add_argument("--control-port", type=int, default=0, help="control port (0 = ephemeral)")
    p.add_argument("--no-control", action="store_true", help="no control port")
    p.add_argument("--stdin-control", action="store_true", help="read control commands on stdin")
    p.add_argument("--profile", type=Path, help="machine profile JSON (default: Shapeoko 4)")
    p.add_argument("--capture", type=Path, help="shapeoko-capture JSON: use its $$ settings")
    p.add_argument("--setting", type=_parse_setting, action="append", default=[], metavar="N=VALUE")
    p.add_argument("--time-scale", type=float, default=1.0, help="simulated seconds per second")
    p.add_argument("--tool", type=float, default=WorldDefaults.tool_length_mm, metavar="MM")
    p.add_argument("--tool-dia", type=float, default=WorldDefaults.tool_diameter_mm, metavar="MM")
    p.add_argument("--bitsetter-top-z", type=float, default=WorldDefaults.bitsetter_top_z)
    p.add_argument("--stock-top-z", type=float, default=WorldDefaults.stock_top_z)
    p.add_argument("--m6", choices=("error", "ignore"), default="error")
    p.add_argument("--no-connect-reset", action="store_true", help="override the profile")
    p.add_argument("-v", "--verbose", action="count", default=0)
    return p


def build_machine(args: argparse.Namespace) -> ShapeokoGrbl:
    profile = load_profile(args.profile)
    if args.no_connect_reset:
        from dataclasses import replace

        profile = replace(profile, connect_resets_controller=False)
    base = None
    if args.capture:
        captured = ShapeokoGrbl.from_capture(json.loads(args.capture.read_text()))
        base = captured.settings
    settings = settings_for(profile, base, dict(args.setting))
    defaults = WorldDefaults(
        tool_length_mm=args.tool,
        bitsetter_top_z=args.bitsetter_top_z,
        stock_top_z=args.stock_top_z,
    )
    machine = ShapeokoGrbl(
        profile,
        settings=settings,
        world=world_for(profile, defaults),
        time_scale=args.time_scale,
        m6=args.m6,
    )
    machine.set_tool_diameter(args.tool_dia)
    return machine


async def _serve(args: argparse.Namespace) -> None:
    server = SimServer(
        build_machine(args),
        host=args.host,
        port=args.port,
        control_port=None if args.no_control else args.control_port,
    )
    await server.start()
    print(f"LISTENING {args.host}:{server.port}", flush=True)
    if server.control_port is not None:
        print(f"CONTROL {args.host}:{server.control_port}", flush=True)
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, server._stopped.set)
    if args.stdin_control:
        threading.Thread(target=_stdin_reader, args=(loop, server), daemon=True).start()
    await server.wait_stopped()
    await server.stop()


def _stdin_reader(loop: asyncio.AbstractEventLoop, server: SimServer) -> None:
    for line in sys.stdin:
        if not line.strip():
            continue
        fut = asyncio.run_coroutine_threadsafe(_async_control(server, line), loop)
        print(fut.result(), flush=True)


async def _async_control(server: SimServer, line: str) -> str:
    return server.control(line)


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    try:
        _require_loopback(args.host)
    except ValueError as exc:
        raise SystemExit(f"ugs-sim: {exc}") from None
    level = logging.WARNING - 10 * min(args.verbose, 2)
    logging.basicConfig(level=level, stream=sys.stderr, format="%(asctime)s %(message)s")
    with contextlib.suppress(KeyboardInterrupt):
        asyncio.run(_serve(args))


if __name__ == "__main__":
    main()

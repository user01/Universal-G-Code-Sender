"""A stand-in for the Java bridge API, for tests (bridge/docs/api.md, contract v1).

Serves the read endpoints, ``POST /command`` (lines go to resurfacer's ``SimulatedGrbl``),
``/realtime``, ``/notify``, ``/authority/request`` and ``WS /events``, with token auth (401)
and the operator lock (423 / WebSocket close 4423). Every record it emits follows
``bridge/schema/log-record.schema.json``.

Optional, for the live viewer (api.md "Additions after W1"): a ``viewer_token`` accepted as
``Authorization: Bearer v.<token>`` on GET endpoints and as ``WS /events?vt=<token>`` (or the
``vt.<token>`` subprotocol), unaffected by the lock; ``GET /file/content`` after ``load_file``;
the viewer's static files at ``/view/`` (``viewer_dir``); CORS for dev pages (``cors_origins``).

It binds 127.0.0.1 on an ephemeral port, writes ``bridge.json`` and ``token`` into the
given home directory (a temp dir in tests, never the real one) and runs uvicorn on a
background thread. It never opens a serial port.

    with FakeBridge(tmp_path) as fake:
        client = BridgeClient(tmp_path)
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import os
import re
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

import uvicorn
from shapeoko_surface.controller.grbl_parser import StatusReport, parse_status
from shapeoko_surface.controller.simulator import SimulatedGrbl
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, PlainTextResponse, Response
from starlette.routing import BaseRoute, Mount, Route, WebSocketRoute
from starlette.staticfiles import StaticFiles
from starlette.websockets import WebSocket, WebSocketDisconnect

from ugs_mcp.config import local_profile
from ugs_mcp.describe import ALARMS, setting_name_units
from ugs_mcp.safety import REALTIME_OPS, Level, LineClass, classify_line, level_allows

API = "/api/bridge/v1"
REALTIME_BYTES = {"hold": "!", "resume": "~", "jog_cancel": "\x85", "reset": "\x18", "status": "?"}
CONSOLE_KINDS = ("message", "tx", "rx", "command")


def _now() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _err(status: int, code: str, message: str, **extra: Any) -> JSONResponse:
    return JSONResponse({"error": code, "message": message, **extra}, status_code=status)


class FakeBridge:
    def __init__(
        self,
        home: Path,
        *,
        token: str = "test-token",
        locked: bool = False,
        level: str = "query",
        connected: bool = True,
        sim: SimulatedGrbl | None = None,
        profile: dict[str, Any] | None = None,
        viewer_token: str | None = None,
        viewer_dir: Path | None = None,
        cors_origins: list[str] | None = None,
        port: int = 0,
    ) -> None:
        self.home = Path(home)
        self.token = token
        self.locked = locked
        self.level = Level(level)
        self.connected = connected
        self.sim = sim or SimulatedGrbl()
        self.profile = profile if profile is not None else (local_profile()[0] or {})
        self.records: list[dict[str, Any]] = []
        self.commands: dict[str, dict[str, Any]] = {}
        self.file_info: dict[str, Any] | None = None
        self.file_text: str | None = None
        self.viewer_token = viewer_token
        self.viewer_dir = viewer_dir
        self.cors_origins = cors_origins
        self._viewer_queues: set[int] = set()
        self.settings_raw: list[str] = []
        self.settings_read_at: str | None = None
        self.homed = False
        self.last_alarm: int | None = None
        self.port = port
        self._seq = 0
        self._req = 0
        self._t0 = time.monotonic()
        self._lock = threading.RLock()
        self._subscribers: list[
            tuple[asyncio.Queue[dict[str, Any] | None], asyncio.AbstractEventLoop, set[str] | None]
        ] = []
        self._last_status: tuple[Any, ...] | None = None
        self._wco = (0.0, 0.0, 0.0)
        self.session = datetime.now().strftime("%Y%m%d-%H%M%S") + "-tcp_127.0.0.1_sim"
        self._server: uvicorn.Server | None = None
        self._thread: threading.Thread | None = None

    # Lifecycle ---------------------------------------------------------------------------

    def __enter__(self) -> FakeBridge:
        return self.start()

    def __exit__(self, *exc: object) -> None:
        self.stop()

    def start(self) -> FakeBridge:
        self.emit(
            "session",
            "bridge",
            {"event": "bridge_start", "ugs_version": "fake", "bridge_version": "fake"},
            session=None,
        )
        if self.connected:
            self.emit(
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
            for line in self._drain():
                self.emit("rx", "controller", {"line": line})
        config = uvicorn.Config(
            self.app(), host="127.0.0.1", port=self.port, log_level="warning", lifespan="off"
        )
        self._server = uvicorn.Server(config)
        self._thread = threading.Thread(target=self._server.run, name="fake-bridge", daemon=True)
        self._thread.start()
        deadline = time.monotonic() + 10
        while not self._server.started:
            if time.monotonic() > deadline or not self._thread.is_alive():
                raise RuntimeError("fake bridge didn't start")
            time.sleep(0.01)
        self.port = int(self._server.servers[0].sockets[0].getsockname()[1])
        self.home.mkdir(parents=True, exist_ok=True)
        self._write_private(
            self.home / "bridge.json",
            json.dumps(
                {
                    "format": "ugs-bridge-discovery/1",
                    "port": self.port,
                    "pid": os.getpid(),
                    "started": _now(),
                    "api": API,
                }
            ),
        )
        self._write_private(self.home / "token", self.token + "\n")
        return self

    def stop(self) -> None:
        """Stop serving. ``bridge.json`` stays behind, like after a UGS crash."""
        if self._server is not None:
            self._server.should_exit = True
        if self._thread is not None:
            self._thread.join(timeout=10)
        self._server = None
        self._thread = None

    @staticmethod
    def _write_private(path: Path, text: str) -> None:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)

    # Operator actions (what the UGS UI would do) ---------------------------------------

    def set_locked(self, locked: bool) -> None:
        with self._lock:
            self.locked = locked
            if locked:
                self.level = Level.QUERY
            self.emit(
                "authority",
                "operator",
                {"locked": locked, "level": self.level.value, "by": "operator"},
            )
            if locked:
                for q, loop, _ in list(self._subscribers):
                    if id(q) not in self._viewer_queues:  # the lock doesn't apply to the viewer
                        loop.call_soon_threadsafe(q.put_nowait, None)

    def set_level(self, level: str) -> None:
        with self._lock:
            self.level = Level(level)
            self.emit(
                "authority",
                "operator",
                {"locked": self.locked, "level": self.level.value, "by": "operator"},
            )

    def load_file(self, path: Path) -> dict[str, Any]:
        """Load a program as the operator would in UGS (``GET /file``, ``/file/content``)."""
        data = Path(path).read_bytes()
        text = data.decode("utf-8", errors="replace")
        with self._lock:
            self.file_text = text
            self.file_info = {
                "path": str(path),
                "sha256": hashlib.sha256(data).hexdigest(),
                "lines": len(text.splitlines()),
                "loaded_at": _now(),
            }
            self.emit(
                "file",
                "operator",
                {k: self.file_info[k] for k in ("path", "sha256", "lines")} | {"action": "loaded"},
            )
            return dict(self.file_info)

    # Records -----------------------------------------------------------------------------

    @property
    def seq(self) -> int:
        return self._seq

    def emit(
        self, kind: str, src: str, data: dict[str, Any], *, session: str | bool | None = True
    ) -> dict[str, Any]:
        with self._lock:
            self._seq += 1
            rec = {
                "seq": self._seq,
                "t": _now(),
                "mono": round(time.monotonic() - self._t0, 3),
                "session": self.session if session is True else (session or None),
                "kind": kind,
                "src": src,
                "data": data,
            }
            self.records.append(rec)
            for q, loop, kinds in list(self._subscribers):
                if kinds is None or kind in kinds:
                    with contextlib.suppress(RuntimeError):
                        loop.call_soon_threadsafe(q.put_nowait, rec)
            return rec

    def _select(
        self, since: int, kinds: set[str] | None, limit: int
    ) -> tuple[list[dict[str, Any]], int]:
        with self._lock:
            out = [
                r
                for r in self.records
                if r["seq"] > since and (kinds is None or r["kind"] in kinds)
            ]
            if len(out) > limit:
                out = out[:limit]
                return out, out[-1]["seq"]
            return out, max(since, self._seq)

    # Simulator ---------------------------------------------------------------------------

    def _drain(self) -> list[str]:
        out: list[str] = []
        while (line := self.sim.readline(0.0)) is not None:
            out.append(line)
        return out

    def _exchange(self, line: str, timeout: float = 2.0) -> list[str]:
        self.sim.write((line + "\n").encode("ascii"))
        replies: list[str] = []
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            r = self.sim.readline(max(0.0, deadline - time.monotonic()))
            if r is None:
                break
            replies.append(r)
            if r == "ok" or r.startswith(("error:", "ALARM:")):
                break
        return replies

    def _poll_status(self) -> dict[str, Any] | None:
        if not self.connected:
            return None
        self.sim.write(b"?")
        raw = None
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline:
            r = self.sim.readline(0.05)
            if r is None:
                continue
            if r.startswith("<"):
                raw = r
                break
            self.emit("rx", "controller", {"line": r})
        if raw is None:
            return None
        rep = parse_status(raw)
        if rep.wco is not None:
            self._wco = rep.wco
        status = self._controller_status(rep, raw)
        key = (
            status["state"],
            tuple(status["mpos"] or ()),
            status["pins"]["raw"],
            tuple(status["wco"] or ()),
        )
        if key != self._last_status:
            self._last_status = key
            self.emit("status", "ugs", status)
        return status

    def _controller_status(self, rep: StatusReport, raw: str) -> dict[str, Any]:
        mpos = list(rep.mpos) if rep.mpos else None
        wco = list(self._wco)
        wpos = [round(m - w, 3) for m, w in zip(mpos, wco, strict=True)] if mpos else None
        pins = "".join(sorted(rep.pins))
        acc = rep.accessories or ""
        st: dict[str, Any] = {
            "state": rep.state
            if rep.state
            in {"Idle", "Run", "Hold", "Jog", "Alarm", "Door", "Check", "Home", "Sleep", "Tool"}
            else "Unknown",
            "substate": rep.substate,
            "mpos": mpos,
            "wpos": wpos,
            "wco": wco,
            "feed": rep.feed,
            "spindle": rep.spindle,
            "pins": {
                "raw": pins,
                "probe": "P" in pins,
                "x_limit": "X" in pins,
                "y_limit": "Y" in pins,
                "z_limit": "Z" in pins,
                "door": "D" in pins,
                "hold": "H" in pins,
                "reset": "R" in pins,
                "start": "S" in pins,
            },
            "accessories": {
                "spindle_cw": "S" in acc,
                "spindle_ccw": "C" in acc,
                "flood": "F" in acc,
                "mist": "M" in acc,
            },
            "buffer": None
            if rep.planner_blocks_free is None
            else {"planner": rep.planner_blocks_free, "rx": rep.rx_bytes_free or 0},
            "line": None,
            "raw": raw,
        }
        if rep.overrides:
            st["overrides"] = dict(zip(("feed", "rapid", "spindle"), rep.overrides, strict=True))
        return st

    # HTTP --------------------------------------------------------------------------------

    def _is_viewer(self, header: str) -> bool:
        return bool(self.viewer_token) and header == f"Bearer v.{self.viewer_token}"

    def _auth(self, request: Request, *, viewer_ok: bool = False) -> Response | None:
        remote = request.client.host if request.client else ""
        header = request.headers.get("authorization", "")
        if viewer_ok and self._is_viewer(header):
            return None  # read-only viewer token: GET only, not affected by the lock
        if header != f"Bearer {self.token}":
            self.emit(
                "client", "bridge", {"client": "mcp", "action": "auth_failed", "remote": remote}
            )
            return _err(401, "unauthorized", "missing or wrong token")
        if self.locked:
            self.emit(
                "client", "bridge", {"client": "mcp", "action": "locked_refused", "remote": remote}
            )
            return _err(423, "locked_by_operator", "UGS access is locked by the operator")
        return None

    async def _json(self, request: Request) -> dict[str, Any] | None:
        try:
            body = await request.json()
        except ValueError:
            return None
        return body if isinstance(body, dict) else None

    def app(self) -> Starlette:
        routes: list[BaseRoute] = [
            Route(f"{API}/status", self.h_status),
            Route(f"{API}/log", self.h_log),
            Route(f"{API}/console", self.h_console),
            Route(f"{API}/settings", self.h_settings),
            Route(f"{API}/file", self.h_file),
            Route(f"{API}/file/content", self.h_file_content),
            Route(f"{API}/profile", self.h_profile),
            Route(f"{API}/origins", self.h_origins),
            Route(f"{API}/job/state", self.h_job_state),
            Route(f"{API}/command/{{cid}}", self.h_command_get),
            Route(f"{API}/command", self.h_command, methods=["POST"]),
            Route(f"{API}/realtime", self.h_realtime, methods=["POST"]),
            Route(f"{API}/notify", self.h_notify, methods=["POST"]),
            Route(f"{API}/authority/request", self.h_authority_request, methods=["POST"]),
            WebSocketRoute(f"{API}/events", self.ws_events),
        ]
        if self.viewer_dir is not None:
            routes.append(Mount("/view", StaticFiles(directory=self.viewer_dir, html=True)))
        middleware = []
        if self.cors_origins:
            middleware.append(
                Middleware(
                    CORSMiddleware,
                    allow_origins=self.cors_origins,
                    allow_methods=["GET"],
                    allow_headers=["Authorization"],
                )
            )
        return Starlette(routes=routes, middleware=middleware)

    def bridge_status(self) -> dict[str, Any]:
        with self._lock:
            ctrl = self._poll_status()
            alarm = None
            if self.last_alarm is not None and ctrl and ctrl["state"] == "Alarm":
                alarm = {"code": self.last_alarm, "text": ALARMS.get(self.last_alarm, "")}
            return {
                "seq": self._seq,
                "connection": {
                    "connected": self.connected,
                    "port": "tcp://127.0.0.1:sim" if self.connected else None,
                    "driver": "TCP" if self.connected else None,
                    "firmware": "Grbl 1.1f" if self.connected else None,
                    "session": self.session if self.connected else None,
                },
                "controller": ctrl,
                "homed_this_connection": self.homed,
                "alarm": alarm,
                "authority": {
                    "locked": self.locked,
                    "level": self.level.value,
                    "motion_expires_at": None,
                },
                "stream": {
                    "state": "idle",
                    "file": None,
                    "sha256": None,
                    "line_sent": None,
                    "line_acked": None,
                    "lines_total": None,
                },
                "interceptor": None,
                "tool": None,
            }

    async def h_status(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        return JSONResponse(self.bridge_status())

    def _query_params(
        self, request: Request, kinds_fixed: tuple[str, ...] | None = None
    ) -> tuple[int, set[str] | None, int] | Response:
        try:
            since = int(request.query_params.get("since", "0"))
            limit = int(request.query_params.get("limit", "1000"))
        except ValueError:
            return _err(400, "invalid_request", "since and limit must be integers")
        limit = max(1, min(limit, 5000))
        if kinds_fixed is not None:
            return since, set(kinds_fixed), limit
        raw = request.query_params.get("kinds")
        return since, (set(raw.split(",")) if raw else None), limit

    async def h_log(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        q = self._query_params(request)
        if isinstance(q, Response):
            return q
        records, nxt = self._select(*q)
        return JSONResponse({"records": records, "next": nxt})

    async def h_console(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        q = self._query_params(request, CONSOLE_KINDS)
        if isinstance(q, Response):
            return q
        records, nxt = self._select(*q)
        return JSONResponse({"records": records, "next": nxt})

    async def h_settings(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        settings: dict[str, Any] = {}
        for line in self.settings_raw:
            if m := re.match(r"^\$(\d+)=(\S+)", line):
                name, units = setting_name_units(int(m.group(1)))
                try:
                    value: Any = float(m.group(2))
                except ValueError:
                    value = m.group(2)
                settings[f"${m.group(1)}"] = {"value": value, "name": name, "units": units}
        return JSONResponse(
            {
                "raw": "\n".join(self.settings_raw),
                "read_at": self.settings_read_at,
                "settings": settings,
            }
        )

    async def h_file(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        if self.file_info is None:
            return _err(404, "not_found", "no file loaded")
        return JSONResponse(self.file_info)

    async def h_file_content(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        if self.file_text is None:
            return _err(404, "not_found", "no file loaded")
        return PlainTextResponse(self.file_text)

    async def h_profile(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        return JSONResponse(self.profile)

    async def h_origins(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        return JSONResponse({"format": "ugs-bridge-origins/1", "origins": []})

    async def h_job_state(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        return _err(404, "not_found", "no job journal")

    async def h_command_get(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        cid = request.path_params["cid"]
        with self._lock:
            result = self.commands.get(cid)
        if result is None:
            return _err(404, "not_found", f"no command {cid}")
        return JSONResponse(result)

    async def h_command(self, request: Request) -> Response:
        if (r := self._auth(request)) is not None:
            return r
        body = await self._json(request)
        lines = body.get("lines") if body else None
        if not isinstance(lines, list) or not lines or not all(isinstance(x, str) for x in lines):
            return _err(400, "invalid_request", "lines must be a non-empty list of strings")
        assert body is not None
        classes = [classify_line(x) for x in lines]
        for c in classes:
            if not level_allows(self.level, c.level):
                return _err(
                    403,
                    "authority_required",
                    f"'{c.line}' needs {c.level.value} authority",
                    required=c.level.value,
                )
        if not self.connected:
            return _err(409, "not_connected", "UGS is not connected to a controller")
        with self._lock:
            self._req += 1
            cid = f"c-{self._req}"
            result = self._run_command(cid, lines, classes, body.get("tag"), body.get("expect"))
        return JSONResponse(
            {
                "id": cid,
                "lines": [
                    {k: ln[k] for k in ("index", "line", "class", "state")}
                    for ln in result["lines"]
                ],
            },
            status_code=202,
        )

    def _run_command(
        self, cid: str, lines: list[str], classes: list[Any], tag: Any, expect: Any
    ) -> dict[str, Any]:
        out_lines: list[dict[str, Any]] = []
        result: dict[str, Any] = {
            "id": cid,
            "tag": tag,
            "expect": expect,
            "state": "running",
            "lines": out_lines,
        }
        self.commands[cid] = result
        stop = False
        for i, (line, c) in enumerate(zip(lines, classes, strict=True)):
            lid = f"{cid}-{i}"
            entry: dict[str, Any] = {
                "index": i,
                "line": line,
                "class": c.cls.value,
                "state": "queued",
                "response": [],
                "error_code": None,
                "alarm_code": None,
            }
            out_lines.append(entry)
            base = {"id": lid, "origin": "claude", "line": line, "tag": tag, "expect": expect}
            if stop:
                entry["state"] = "dropped"
                self.emit("command", "claude", {**base, "state": "dropped"})
                continue
            if c.cls is LineClass.CONFIRM:
                entry["state"] = "awaiting_operator"
                self.emit("command", "claude", {**base, "state": "awaiting_operator"})
                stop = True
                continue
            self.emit("command", "claude", {**base, "state": "queued"})
            if c.line == "?":
                st = self._poll_status()
                entry["response"] = [st["raw"]] if st else []
                entry["state"] = "ok"
                self.emit(
                    "command", "claude", {**base, "state": "ok", "response": entry["response"]}
                )
                continue
            self.emit("tx", "claude", {"line": c.line, "cmd_id": lid})
            self.emit("command", "claude", {**base, "state": "sent"})
            replies = self._exchange(c.line)
            for rline in replies:
                self.emit("rx", "claude", {"line": rline, "cmd_id": lid})
            entry["response"] = replies
            last = replies[-1] if replies else ""
            if last == "ok":
                entry["state"] = "ok"
                if c.line == "$$":
                    self.settings_raw = [x for x in replies if x.startswith("$")]
                    self.settings_read_at = _now()
                    self.emit("event", "ugs", {"type": "setting", "count": len(self.settings_raw)})
                if c.line == "$H":
                    self.homed = True
            elif last.startswith("error:"):
                entry["state"] = "error"
                entry["error_code"] = int(last[6:])
                stop = True
            elif last.startswith("ALARM:"):
                entry["state"] = "alarm"
                entry["alarm_code"] = self.last_alarm = int(last[6:])
                self.emit("event", "ugs", {"type": "alarm", "code": self.last_alarm})
                stop = True
            else:
                entry["state"] = "error"
                stop = True
            self.emit(
                "command",
                "claude",
                {
                    **base,
                    "state": entry["state"],
                    "response": replies,
                    "error_code": entry["error_code"],
                    "alarm_code": entry["alarm_code"],
                },
            )
        states = {ln["state"] for ln in out_lines}
        if "awaiting_operator" in states:
            result["state"] = "awaiting_operator"
        elif states <= {"ok"}:
            result["state"] = "done"
        elif "dropped" in states and not states & {"error", "alarm"}:
            result["state"] = "dropped"
        else:
            result["state"] = "failed"
        return result

    async def h_realtime(self, request: Request) -> Response:
        if (r := self._auth(request)) is not None:
            return r
        body = await self._json(request)
        op = body.get("op") if body else None
        if op not in REALTIME_OPS:
            return _err(400, "invalid_request", f"op must be one of {sorted(REALTIME_OPS)}")
        assert isinstance(op, str)
        byte = REALTIME_BYTES[op]
        code = f"0x{ord(byte):02x}"
        with self._lock:
            if op == "status":
                self._poll_status()
            else:
                self.sim.write(byte.encode("latin-1"))
                self.emit("tx", "claude", {"line": "", "realtime": code})
                for line in self._drain():
                    self.emit("rx", "controller", {"line": line})
        return JSONResponse({"sent": code})

    async def h_notify(self, request: Request) -> Response:
        if (r := self._auth(request)) is not None:
            return r
        body = await self._json(request)
        text = body.get("text") if body else None
        level = (body or {}).get("level", "info")
        if not isinstance(text, str) or not text or level not in ("info", "warn", "alert"):
            return _err(400, "invalid_request", "need text and level info|warn|alert")
        self.emit(
            "notify",
            "claude",
            {
                "trigger": "claude",
                "text": text,
                "level": level,
                "sinks": {"telegram": "disabled", "slack": "disabled"},
            },
        )
        return JSONResponse({}, status_code=202)

    async def h_authority_request(self, request: Request) -> Response:
        if (r := self._auth(request)) is not None:
            return r
        body = await self._json(request)
        level = (body or {}).get("level")
        reason = (body or {}).get("reason", "")
        if level not in ("motion", "stream") or not isinstance(reason, str):
            return _err(400, "invalid_request", "level must be motion or stream, with a reason")
        self.emit(
            "authority",
            "claude",
            {
                "locked": self.locked,
                "level": self.level.value,
                "reason": reason,
                "by": "claude",
                "requested": level,
            },
        )
        return JSONResponse({}, status_code=202)

    def _ws_viewer(self, ws: WebSocket) -> tuple[bool, str | None]:
        """Viewer auth for a WebSocket: ``?vt=<token>`` or the ``vt.<token>`` subprotocol."""
        if not self.viewer_token:
            return False, None
        vt = ws.query_params.get("vt")
        if vt is not None:
            return vt.removeprefix("v.") == self.viewer_token, None
        protocols = [
            p.strip() for p in ws.headers.get("sec-websocket-protocol", "").split(",") if p.strip()
        ]
        for p in protocols:
            if p.startswith("vt."):
                ok = p.removeprefix("vt.").removeprefix("v.") == self.viewer_token
                return ok, ("ugs-bridge.v1" if "ugs-bridge.v1" in protocols else None)
        return False, None

    async def ws_events(self, ws: WebSocket) -> None:
        header = ws.headers.get("authorization", "")
        remote = ws.client.host if ws.client else ""
        viewer, subprotocol = self._ws_viewer(ws)
        has_vt = "vt" in ws.query_params or "vt." in ws.headers.get("sec-websocket-protocol", "")
        if has_vt and not viewer:
            self.emit(
                "client", "bridge", {"client": "viewer", "action": "auth_failed", "remote": remote}
            )
            await self._deny(ws, 401, "unauthorized")
            return
        # the viewer is read-only and not affected by the lock; the MCP client is
        if not viewer and header != f"Bearer {self.token}":
            self.emit(
                "client", "bridge", {"client": "mcp", "action": "auth_failed", "remote": remote}
            )
            await self._deny(ws, 401, "unauthorized")
            return
        if not viewer and self.locked:
            self.emit(
                "client", "bridge", {"client": "mcp", "action": "locked_refused", "remote": remote}
            )
            await self._deny(ws, 423, "locked_by_operator")
            return
        try:
            since = int(ws.query_params.get("since", "0"))
        except ValueError:
            since = 0
        raw = ws.query_params.get("kinds")
        kinds = set(raw.split(",")) if raw else None
        await ws.accept(subprotocol=subprotocol)
        q: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
        sub = (q, asyncio.get_running_loop(), kinds)
        with self._lock:
            backlog, _ = self._select(since, kinds, 1_000_000)
            self._subscribers.append(sub)
            if viewer:
                self._viewer_queues.add(id(q))
        client = "viewer" if viewer else "mcp"
        self.emit("client", "bridge", {"client": client, "action": "connect", "remote": remote})
        sent = since
        try:
            for rec in backlog:
                await ws.send_text(json.dumps(rec))
                sent = rec["seq"]
            while True:
                item = await q.get()
                if item is None:
                    await ws.close(code=4423, reason="locked_by_operator")
                    return
                if item["seq"] > sent:
                    await ws.send_text(json.dumps(item))
                    sent = item["seq"]
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            with self._lock:
                if sub in self._subscribers:
                    self._subscribers.remove(sub)
                self._viewer_queues.discard(id(q))

    async def _deny(self, ws: WebSocket, status: int, code: str) -> None:
        message = (
            "UGS access is locked by the operator" if status == 423 else "missing or wrong token"
        )
        if "websocket.http.response" in ws.scope.get("extensions", {}):
            await ws.send_denial_response(_err(status, code, message))
        else:
            await ws.accept()
            await ws.close(code=4000 + status, reason=code)

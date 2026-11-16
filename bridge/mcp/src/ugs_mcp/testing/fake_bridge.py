"""A stand-in for the Java bridge API, for tests (bridge/docs/api.md, contract v1).

Serves the read endpoints, ``POST /command`` (lines go to a simulated GRBL: resurfacer's
``SimulatedGrbl`` by default, or the workspace's ``ugs_sim.machine.ShapeokoGrbl`` with the
BitSetter and BitZero), ``/realtime``, ``/notify``, ``/authority/request``, ``/origins``,
``/origins/apply``, ``/file/load``, ``/file/run``, ``/job/state``, ``/job/resume/plan``,
``/job/resume`` and ``WS /events``, with token auth (401), the operator lock (423 on actions;
reads and the event stream stay open) and authority levels (403 with ``required``). Every
record it emits follows ``bridge/schema/log-record.schema.json``.

Like UGS, commands run in order on one queue (a worker thread), so ``POST /command`` answers
202 at once and results arrive at ``GET /command/{id}``. ``$H``/``$X`` from Claude, file runs
and resumes wait in ``awaiting_operator`` until a test plays the operator with
:meth:`FakeBridge.confirm_operator`. Motion and Stream authority expire on an alarm or a
reset, as SPEC §4.3 says.

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
import queue
import re
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
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
from ugs_mcp.gcode.lexer import split_comments
from ugs_mcp.safety import (
    REALTIME_OPS,
    Classification,
    Level,
    LineClass,
    classify_line,
    level_allows,
)

API = "/api/bridge/v1"
REALTIME_BYTES = {"hold": "!", "resume": "~", "jog_cancel": "\x85", "reset": "\x18", "status": "?"}
CONSOLE_KINDS = ("message", "tx", "rx", "command")
POSITION_LOST_ALARMS = frozenset({1, 2, 3})
_G54 = re.compile(r"^\[G54:([-\d.]+),([-\d.]+),([-\d.]+)\]$")
_G10 = re.compile(r"G10")


def _now() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _err(status: int, code: str, message: str, **extra: Any) -> JSONResponse:
    return JSONResponse({"error": code, "message": message, **extra}, status_code=status)


@dataclass
class Dialog:
    """An operator confirmation the UGS GUI would show."""

    id: str
    kind: str
    """``line`` ($H/$X from Claude), ``file_run`` or ``resume``."""
    detail: str
    event: threading.Event = field(default_factory=threading.Event)
    accepted: bool | None = None
    on_accept: Callable[[], None] | None = None


@dataclass
class _Job:
    cid: str
    lines: list[str]
    classes: list[Classification]
    tag: Any
    expect: Any
    origin: str
    timeout: float
    result: dict[str, Any]
    gen: int
    stream: bool = False
    after: Callable[[dict[str, Any]], None] | None = None


@dataclass
class _Inflight:
    lid: str | None
    src: str
    replies: list[str] = field(default_factory=list)


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
        self.origins: list[dict[str, Any]] = []
        self.job_state: dict[str, Any] | None = None
        self.plans: dict[str, dict[str, Any]] = {}
        self.dialogs: list[Dialog] = []
        self.stream: dict[str, Any] = {"state": "idle"}
        self.port = port
        self._seq = 0
        self._req = 0
        self._gen = 0
        self._t0 = time.monotonic()
        self._lock = threading.RLock()
        self._subscribers: list[
            tuple[asyncio.Queue[dict[str, Any] | None], asyncio.AbstractEventLoop, set[str] | None]
        ] = []
        self._last_status: tuple[Any, ...] | None = None
        self._last_ctrl: dict[str, Any] | None = None
        self._wco = (0.0, 0.0, 0.0)
        self._inflight: _Inflight | None = None
        self._jobs: queue.Queue[_Job | None] = queue.Queue()
        self.session = datetime.now().strftime("%Y%m%d-%H%M%S") + "-tcp_127.0.0.1_sim"
        self._server: uvicorn.Server | None = None
        self._thread: threading.Thread | None = None
        self._worker: threading.Thread | None = None
        self._stopping = False

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
            with self._lock:
                self._pump()
        self._stopping = False
        self._worker = threading.Thread(target=self._work, name="fake-bridge-queue", daemon=True)
        self._worker.start()
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
        self._stopping = True
        with self._lock:
            self._gen += 1
        for d in self.dialogs:
            d.event.set()
        if self._server is not None:
            self._server.should_exit = True
        if self._thread is not None:
            self._thread.join(timeout=10)
        if self._worker is not None:
            self._jobs.put(None)
            self._worker.join(timeout=10)
        self._server = None
        self._thread = None
        self._worker = None

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
                self._gen += 1  # drop Claude's queued commands
                for d in self.dialogs:
                    if d.accepted is None:
                        d.accepted = False
                        d.event.set()
            self.emit(
                "authority",
                "operator",
                {"locked": locked, "level": self.level.value, "by": "operator"},
            )

    def set_level(self, level: str) -> None:
        with self._lock:
            self.level = Level(level)
            self.emit(
                "authority",
                "operator",
                {"locked": self.locked, "level": self.level.value, "by": "operator"},
            )

    def pending_dialogs(self, kind: str | None = None) -> list[Dialog]:
        with self._lock:
            return [
                d for d in self.dialogs if d.accepted is None and (kind is None or d.kind == kind)
            ]

    def confirm_operator(
        self, *, accept: bool = True, kind: str | None = None, timeout: float = 5.0
    ) -> Dialog:
        """Answer the oldest open dialog (waiting up to ``timeout`` for one to appear)."""
        deadline = time.monotonic() + timeout
        while True:
            open_ = self.pending_dialogs(kind)
            if open_:
                break
            if time.monotonic() > deadline:
                raise TimeoutError(f"no operator dialog ({kind or 'any'}) appeared")
            time.sleep(0.01)
        d = open_[0]
        d.accepted = accept
        if accept and d.on_accept is not None:
            d.on_accept()
        d.event.set()
        return d

    def operator_command(self, lines: list[str], timeout: float = 30.0) -> dict[str, Any]:
        """The operator types lines in the UGS console; waits for the result."""
        cid = self._enqueue(lines, origin="operator", tag=None, expect=None, timeout=timeout)
        return self.wait_result(cid, timeout)

    def operator_home(self, timeout: float = 30.0) -> dict[str, Any]:
        return self.operator_command(["$H"], timeout)

    def wait_result(self, cid: str, timeout: float = 30.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self._lock:
                result = self.commands[cid]
                if result["state"] in ("done", "failed", "dropped"):
                    return result
            time.sleep(0.005)
        raise TimeoutError(f"command {cid} still {self.commands[cid]['state']}")

    def wait_idle(self, timeout: float = 30.0) -> dict[str, Any] | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self._lock:
                st = self._poll_status()
            if st and st["state"] == "Idle":
                return st
            time.sleep(0.01)
        return None

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

    # Simulator I/O (always under self._lock) --------------------------------------------

    def _route(self, line: str) -> dict[str, Any] | None:
        """One line from the controller: a status report, a reply or unsolicited output."""
        if line.startswith("<"):
            return self._status_from(line)
        inflight = self._inflight
        if inflight is not None:
            inflight.replies.append(line)
            self.emit("rx", inflight.src, {"line": line, "cmd_id": inflight.lid})
        else:
            self.emit("rx", "controller", {"line": line})
        if line.startswith("ALARM:"):
            with contextlib.suppress(ValueError):
                self._on_alarm(int(line[6:]))
        return None

    def _pump(self) -> None:
        while (line := self.sim.readline(0.0)) is not None:
            self._route(line)

    def _on_alarm(self, code: int) -> None:
        self.last_alarm = code
        self.emit("event", "ugs", {"type": "alarm", "code": code})
        if code in POSITION_LOST_ALARMS:
            self.homed = False
        self._expire_motion(f"alarm {code}")

    def _expire_motion(self, why: str) -> None:
        if level_allows(self.level, Level.MOTION):
            self.level = Level.QUERY
            self.emit(
                "authority",
                "bridge",
                {"locked": self.locked, "level": self.level.value, "by": "bridge", "reason": why},
            )

    def _poll_status(self) -> dict[str, Any] | None:
        if not self.connected:
            return None
        self.sim.write(b"?")
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline:
            r = self.sim.readline(0.05)
            if r is None:
                continue
            st = self._route(r)
            if st is not None:
                return st
        return None

    def _status_from(self, raw: str) -> dict[str, Any]:
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
        self._last_ctrl = status
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

    # Command queue -----------------------------------------------------------------------

    def _enqueue(
        self,
        lines: list[str],
        *,
        origin: str,
        tag: Any,
        expect: Any,
        timeout: float,
        stream: bool = False,
        after: Callable[[dict[str, Any]], None] | None = None,
    ) -> str:
        classes = [classify_line(x) for x in lines]
        with self._lock:
            self._req += 1
            cid = f"{'s' if stream else 'c'}-{self._req}"
            entries = []
            for i, (line, c) in enumerate(zip(lines, classes, strict=True)):
                entries.append(
                    {
                        "index": i,
                        "line": line,
                        "class": c.cls.value,
                        "state": "queued",
                        "response": [],
                        "error_code": None,
                        "alarm_code": None,
                    }
                )
                if not stream:
                    self.emit(
                        "command",
                        origin,
                        {
                            "id": f"{cid}-{i}",
                            "origin": origin,
                            "line": line,
                            "tag": tag,
                            "expect": expect,
                            "state": "queued",
                        },
                    )
            result: dict[str, Any] = {
                "id": cid,
                "tag": tag,
                "expect": expect,
                "state": "running",
                "lines": entries,
            }
            self.commands[cid] = result
            job = _Job(
                cid,
                lines,
                classes,
                tag,
                expect,
                origin,
                timeout,
                result,
                self._gen,
                stream=stream,
                after=after,
            )
        self._jobs.put(job)
        return cid

    def _work(self) -> None:
        while True:
            job = self._jobs.get()
            if job is None or self._stopping:
                return
            try:
                self._run_job(job)
            finally:
                if job.after is not None:
                    job.after(job.result)

    def _cmd_record(self, job: _Job, i: int, state: str, **extra: Any) -> None:
        if job.stream and state in ("ok", "sent", "queued"):
            return  # D13: no command records for successful file lines
        self.emit(
            "command",
            job.origin,
            {
                "id": f"{job.cid}-{i}",
                "origin": job.origin,
                "line": job.lines[i],
                "tag": job.tag,
                "expect": job.expect,
                "state": state,
                **extra,
            },
        )

    def _run_job(self, job: _Job) -> None:
        result = job.result
        stop = False
        for i, (line, c) in enumerate(zip(job.lines, job.classes, strict=True)):
            entry = result["lines"][i]
            if stop or job.gen != self._gen:
                entry["state"] = "dropped"
                self._cmd_record(job, i, "dropped")
                continue
            confirm = c.cls is LineClass.CONFIRM and job.origin == "claude"
            if confirm and not self._await_operator(job, i, line):
                entry["state"] = "dropped"
                self._cmd_record(job, i, "dropped")
                stop = True
                continue
            code_part, _, _ = split_comments(line)
            norm = "".join(code_part.split()).upper()
            if norm == "?":
                with self._lock:
                    st = self._poll_status()
                entry["response"] = [st["raw"]] if st else []
                entry["state"] = "ok"
                self._cmd_record(job, i, "ok", response=entry["response"])
                continue
            replies, dropped = self._exchange(job, i, norm)
            entry["response"] = replies
            if dropped:
                entry["state"] = "dropped"
                self._cmd_record(job, i, "dropped", response=replies)
                stop = True
                continue
            with self._lock:
                stop = self._settle(job, i, norm, entry, replies) or stop
        states = {ln["state"] for ln in result["lines"]}
        with self._lock:
            if states <= {"ok"}:
                result["state"] = "done"
            elif "dropped" in states and not states & {"error", "alarm"}:
                result["state"] = "dropped"
            else:
                result["state"] = "failed"

    def _await_operator(self, job: _Job, i: int, line: str) -> bool:
        entry = job.result["lines"][i]
        with self._lock:
            entry["state"] = "awaiting_operator"
            job.result["state"] = "awaiting_operator"
            d = Dialog(f"d-{len(self.dialogs) + 1}", "line", line.strip())
            self.dialogs.append(d)
            self._cmd_record(job, i, "awaiting_operator")
        while not d.event.wait(0.02):
            if job.gen != self._gen or self._stopping:
                d.accepted = False
                break
        with self._lock:
            job.result["state"] = "running"
        return bool(d.accepted) and job.gen == self._gen

    def _exchange(self, job: _Job, i: int, norm: str) -> tuple[list[str], bool]:
        lid = f"{job.cid}-{i}"
        src = job.origin if job.origin != "bridge" else "bridge"
        with self._lock:
            tx: dict[str, Any] = {"line": norm, "cmd_id": lid}
            if job.stream:
                tx["file_line"] = i + 1
            self.emit("tx", src, tx)
            self._cmd_record(job, i, "sent")
            job.result["lines"][i]["state"] = "sent"
            self._inflight = _Inflight(lid, src)
            self.sim.write((norm + "\n").encode("ascii", errors="replace"))
        deadline = time.monotonic() + job.timeout
        try:
            while time.monotonic() < deadline:
                with self._lock:
                    self._pump()
                    assert self._inflight is not None
                    replies = list(self._inflight.replies)
                last = replies[-1] if replies else ""
                if last == "ok" or last.startswith(("error:", "ALARM:")):
                    return replies, False
                if job.gen != self._gen or self._stopping:
                    return replies, True
                time.sleep(0.002)
            return replies, False
        finally:
            with self._lock:
                self._inflight = None

    def _settle(
        self, job: _Job, i: int, norm: str, entry: dict[str, Any], replies: list[str]
    ) -> bool:
        """Record the outcome of one line; return True if the rest must be dropped."""
        last = replies[-1] if replies else ""
        stop = False
        if last == "ok":
            entry["state"] = "ok"
            if norm == "$$":
                self.settings_raw = [x for x in replies if x.startswith("$")]
                self.settings_read_at = _now()
                self.emit("event", "ugs", {"type": "setting", "count": len(self.settings_raw)})
            elif norm == "$H":
                self.homed = True
            elif _G10.search(norm) and job.tag != "apply-origin":
                self._record_origin(job)
        elif last.startswith("error:"):
            entry["state"] = "error"
            entry["error_code"] = int(last[6:])
            stop = True
        elif last.startswith("ALARM:"):
            entry["state"] = "alarm"
            entry["alarm_code"] = int(last[6:])
            stop = True
        else:
            entry["state"] = "error"  # no answer within the timeout
            stop = True
        self._cmd_record(
            job,
            i,
            entry["state"],
            response=replies,
            error_code=entry["error_code"],
            alarm_code=entry["alarm_code"],
        )
        return stop

    def _record_origin(self, job: _Job, method: str = "g10", origin_id: str | None = None) -> None:
        wco = getattr(self.sim, "wco", {}).get(54)
        if wco is None:
            return
        vec = [round(float(v), 3) for v in wco]
        oid = origin_id or f"o-{len(self.origins) + 1}"
        if origin_id is None:
            self.origins.append(
                {
                    "id": oid,
                    "name": None,
                    "wcs": "G54",
                    "wco": vec,
                    "method": method,
                    "tool": None,
                    "bitsetter_ref": None,
                    "created": _now(),
                    "session": self.session,
                    "job_sha256": None,
                    "pinned_to": [],
                    "note": f"recorded from {job.origin} command {job.cid}",
                }
            )
        self.emit(
            "origin",
            "bridge",
            {"wcs": "G54", "wco": vec, "method": method, "origin_id": oid},
        )

    # HTTP --------------------------------------------------------------------------------

    def _is_viewer(self, header: str) -> bool:
        return bool(self.viewer_token) and header == f"Bearer v.{self.viewer_token}"

    def _auth(
        self, request: Request, level: Level = Level.OBSERVE, *, viewer_ok: bool = False
    ) -> Response | None:
        remote = request.client.host if request.client else ""
        header = request.headers.get("authorization", "")
        if viewer_ok and self._is_viewer(header):
            return None  # read-only viewer token: GET only, not affected by the lock
        if header != f"Bearer {self.token}":
            self.emit(
                "client", "bridge", {"client": "mcp", "action": "auth_failed", "remote": remote}
            )
            return _err(401, "unauthorized", "missing or wrong token")
        if self.locked and request.method != "GET":  # the lock gates actions; reads always work
            self.emit(
                "client", "bridge", {"client": "mcp", "action": "locked_refused", "remote": remote}
            )
            return _err(423, "locked_by_operator", "UGS access is locked by the operator")
        if not level_allows(self.level, level):
            return _err(
                403,
                "authority_required",
                f"needs {level.value} authority (granted: {self.level.value})",
                required=level.value,
            )
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
            Route(f"{API}/origins/apply", self.h_origins_apply, methods=["POST"]),
            Route(f"{API}/job/state", self.h_job_state),
            Route(f"{API}/job/resume/plan", self.h_resume_plan, methods=["POST"]),
            Route(f"{API}/job/resume", self.h_resume, methods=["POST"]),
            Route(f"{API}/file/load", self.h_file_load, methods=["POST"]),
            Route(f"{API}/file/run", self.h_file_run, methods=["POST"]),
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
            stream = {
                "state": "idle",
                "file": None,
                "sha256": None,
                "line_sent": None,
                "line_acked": None,
                "lines_total": None,
                **self.stream,
            }
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
                "stream": stream,
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
        with self._lock:
            return JSONResponse({"format": "ugs-bridge-origins/1", "origins": list(self.origins)})

    async def h_origins_apply(self, request: Request) -> Response:
        if (r := self._auth(request, Level.MOTION)) is not None:
            return r
        body = await self._json(request) or {}
        oid = body.get("origin_id")
        origin = next((o for o in self.origins if o["id"] == oid), None)
        if origin is None:
            return _err(404, "not_found", f"no origin {oid!r}")
        if not self.homed:
            return _err(409, "not_homed", "home the machine before applying an origin")
        x, y, z = (f"{float(v):.3f}" for v in origin["wco"])

        def applied(result: dict[str, Any]) -> None:
            if result["state"] == "done":
                job = _Job(result["id"], [], [], "apply-origin", None, "claude", 0, result, 0)
                with self._lock:
                    self._record_origin(job, method="restored", origin_id=origin["id"])

        cid = self._enqueue(
            [f"G10 L2 P1 X{x} Y{y} Z{z}", "$#"],
            origin="claude",
            tag="apply-origin",
            expect=f"G54 = {origin['wco']} (origin {oid})",
            timeout=10.0,
            after=applied,
        )
        return JSONResponse({"command_id": cid}, status_code=202)

    async def h_job_state(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        if self.job_state is None:
            return _err(404, "not_found", "no job journal")
        return JSONResponse(self.job_state)

    async def h_resume_plan(self, request: Request) -> Response:
        if (r := self._auth(request)) is not None:
            return r
        body = await self._json(request) or {}
        target = body.get("target")
        if not isinstance(target, dict) or not (
            isinstance(target.get("line"), int)
            or isinstance(target.get("operation"), str | int)
            or target.get("stopped") is True
        ):
            return _err(
                400,
                "invalid_request",
                "target must be {line: n}, {operation: name|number} or {stopped: true}",
            )
        sha = (self.job_state or {}).get("file", {}).get("sha256") or (
            (self.file_info or {}).get("sha256")
        )
        if not sha:
            return _err(404, "not_found", "no job file loaded and no journal")
        with self._lock:
            plan = self._canned_plan(target, str(sha))
            self.plans[plan["plan_id"]] = plan
            self.emit(
                "job",
                "bridge",
                {"event": "resume_planned", "file_sha256": sha, "line": plan["resume_line"]},
            )
        return JSONResponse(plan)

    def _canned_plan(self, target: dict[str, Any], sha: str) -> dict[str, Any]:
        """A plan shaped like the Java ``ResumePlanner``'s (schema job-state ResumePlan)."""
        js = self.job_state or {}
        if isinstance(target.get("line"), int):
            line = int(target["line"])
        elif target.get("stopped"):
            line = int(js.get("line_done") or 1)
        else:
            line = 120
        wco = js.get("wco") or list(self._wco)
        cur = list(self._wco)
        last = js.get("last_mpos")
        x, y = (last[0] - wco[0], last[1] - wco[1]) if last else (10.0, 20.0)
        origin_ok = all(abs(a - b) < 0.01 for a, b in zip(wco, cur, strict=True))
        requires: list[str] = []
        if not self.homed:
            requires.append("home")
        if not origin_ok:
            requires.append("restore_origin")
        requires.append("tool_measure")
        return {
            "plan_id": f"p-{len(self.plans) + 1}",
            "file_sha256": sha,
            "requested": target,
            "resume_line": line,
            "safe_point": True,
            "operation": js.get("operation") or "OP 03 Pocket",
            "tool": js.get("tool") or "T21",
            "checks": [
                {"name": "journal_file", "ok": True, "detail": "journal matches the file"},
                {
                    "name": "resume_point",
                    "ok": True,
                    "detail": f"line {line} (last done {js.get('line_done', '?')})",
                },
                {
                    "name": "homed",
                    "ok": self.homed,
                    "detail": "homed this connection" if self.homed else "not homed",
                },
                {"name": "tool", "ok": True, "detail": "T21 in the collet"},
                {
                    "name": "tool_length",
                    "ok": False,
                    "detail": "not measured since the stop; measure on the BitSetter",
                },
                {
                    "name": "origin",
                    "ok": origin_ok,
                    "detail": "WCO matches the journal"
                    if origin_ok
                    else f"current WCO {cur} differs from the journal's {wco}",
                },
                {"name": "safe_point", "ok": True, "detail": "at clearance Z5"},
                {"name": "clearance", "ok": True, "detail": "Z5 from header"},
                {"name": "plunge_feed", "ok": True, "detail": "300 mm/min"},
                {"name": "envelope", "ok": True, "detail": "safe Z and resume XY inside"},
            ],
            "requires": requires,
            "preamble": [
                "G21 G53 G0 Z-5",
                "G21 G90 G17 G94 G54",
                f"G0 X{x:.3f} Y{y:.3f}",
                "G0 Z5",
                "M3",
                "G4 P2.0",
                "G1 Z-1.5 F300",
                "F1200",
            ],
            "warnings": [f"Resuming at line {line}: the stock above it is already cut"],
        }

    async def h_resume(self, request: Request) -> Response:
        if (r := self._auth(request, Level.STREAM)) is not None:
            return r
        body = await self._json(request) or {}
        plan = self.plans.get(str(body.get("plan_id")))
        if plan is None:
            return _err(404, "not_found", f"no plan {body.get('plan_id')!r}")

        def accepted() -> None:
            with self._lock:
                self.stream = {"state": "running", "sha256": plan["file_sha256"]}
                self.emit(
                    "job",
                    "operator",
                    {
                        "event": "resume_started",
                        "file_sha256": plan["file_sha256"],
                        "line": plan["resume_line"],
                    },
                )

        with self._lock:
            self.dialogs.append(
                Dialog(
                    f"d-{len(self.dialogs) + 1}",
                    "resume",
                    f"resume {plan['plan_id']} at line {plan['resume_line']}",
                    on_accept=accepted,
                )
            )
        return JSONResponse({"state": "awaiting_operator"}, status_code=202)

    async def h_file_load(self, request: Request) -> Response:
        if (r := self._auth(request, Level.MOTION)) is not None:
            return r
        body = await self._json(request) or {}
        path = Path(str(body.get("path") or ""))
        if not path.is_file():
            return _err(400, "invalid_request", f"{path} is not a readable file")
        data = path.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        with self._lock:
            self.file_info = {
                "path": str(path),
                "sha256": sha,
                "lines": len(data.decode("utf-8", errors="replace").splitlines()),
                "loaded_at": _now(),
            }
            self.emit(
                "file",
                "claude",
                {
                    "action": "loaded",
                    "path": str(path),
                    "sha256": sha,
                    "lines": self.file_info["lines"],
                },
            )
        return JSONResponse(self.file_info)

    async def h_file_run(self, request: Request) -> Response:
        if (r := self._auth(request, Level.STREAM)) is not None:
            return r
        info = self.file_info
        if info is None:
            return _err(404, "not_found", "no file loaded")
        if self.stream.get("state") == "running":
            return _err(409, "busy", "a file is already streaming")

        def accepted() -> None:
            text = Path(info["path"]).read_text(encoding="utf-8", errors="replace")
            lines = []
            for raw in text.splitlines():
                code, _, _ = split_comments(raw)
                if code.strip():
                    lines.append(code.strip())
            with self._lock:
                self.stream = {
                    "state": "running",
                    "file": info["path"],
                    "sha256": info["sha256"],
                    "lines_total": len(lines),
                }
                self.emit(
                    "file",
                    "operator",
                    {"action": "stream_start", "path": info["path"], "sha256": info["sha256"]},
                )

            def finished(result: dict[str, Any]) -> None:
                with self._lock:
                    self.stream["state"] = "completed" if result["state"] == "done" else "error"
                    self.emit(
                        "file",
                        "ugs",
                        {"action": "stream_end", "path": info["path"], "sha256": info["sha256"]},
                    )

            self._enqueue(
                lines,
                origin="ugs",
                tag="stream",
                expect=None,
                timeout=60.0,
                stream=True,
                after=finished,
            )

        with self._lock:
            self.dialogs.append(
                Dialog(
                    f"d-{len(self.dialogs) + 1}",
                    "file_run",
                    f"run {Path(info['path']).name} sha256 {info['sha256'][:12]}",
                    on_accept=accepted,
                )
            )
        return JSONResponse({"state": "awaiting_operator"}, status_code=202)

    async def h_command_get(self, request: Request) -> Response:
        if (r := self._auth(request, viewer_ok=True)) is not None:
            return r
        cid = request.path_params["cid"]
        with self._lock:
            result = self.commands.get(cid)
            snapshot = json.loads(json.dumps(result)) if result is not None else None
        if snapshot is None:
            return _err(404, "not_found", f"no command {cid}")
        return JSONResponse(snapshot)

    async def h_command(self, request: Request) -> Response:
        if (r := self._auth(request)) is not None:
            return r
        body = await self._json(request)
        lines = body.get("lines") if body else None
        if not isinstance(lines, list) or not lines or not all(isinstance(x, str) for x in lines):
            return _err(400, "invalid_request", "lines must be a non-empty list of strings")
        assert body is not None
        for c in (classify_line(x) for x in lines):
            if not level_allows(self.level, c.level):
                return _err(
                    403,
                    "authority_required",
                    f"'{c.line}' needs {c.level.value} authority",
                    required=c.level.value,
                )
        if not self.connected:
            return _err(409, "not_connected", "UGS is not connected to a controller")
        timeout = body.get("timeout_s")
        cid = self._enqueue(
            lines,
            origin="claude",
            tag=body.get("tag"),
            expect=body.get("expect"),
            timeout=float(timeout) if isinstance(timeout, int | float) and timeout > 0 else 60.0,
        )
        with self._lock:
            result = self.commands[cid]
            summary = [
                {k: ln[k] for k in ("index", "line", "class", "state")} for ln in result["lines"]
            ]
        return JSONResponse({"id": cid, "lines": summary}, status_code=202)

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
                if op == "reset":
                    self._gen += 1  # queued lines are dropped
                    for d in self.dialogs:
                        if d.accepted is None:
                            d.accepted = False
                            d.event.set()
                self.sim.write(byte.encode("latin-1"))
                self.emit("tx", "claude", {"line": "", "realtime": code})
                inflight, self._inflight = self._inflight, None
                self._pump()
                self._inflight = inflight
                if op == "reset":
                    self._expire_motion("reset")
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

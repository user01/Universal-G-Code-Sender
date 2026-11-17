"""HTTP + WebSocket client for the bridge API (bridge/docs/api.md, contract v1).

Discovery is re-read on every request: UGS writes a new token on each launch and may pick
another port. Every failure becomes a :class:`BridgeError` whose message is fit to show to
Claude as is (no stack traces).
"""

from __future__ import annotations

import json
import time
from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import anyio
import httpx
from websockets.asyncio.client import connect as ws_connect
from websockets.exceptions import ConnectionClosed, InvalidStatus, WebSocketException

from ugs_mcp.config import bridge_home

DISCOVERY_FORMAT = "ugs-bridge-discovery/1"
LOCKED_MESSAGE = "UGS access is locked by the operator"
NOT_RUNNING = "UGS bridge isn't running"
TERMINAL_COMMAND_STATES = frozenset({"done", "failed", "dropped"})


class BridgeError(Exception):
    """A failure with a message meant for the user/model, plus the API details."""

    def __init__(
        self,
        message: str,
        *,
        status: int | None = None,
        code: str | None = None,
        required: str | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status = status
        self.code = code
        self.required = required


class BridgeNotRunningError(BridgeError):
    pass


class BridgeLockedError(BridgeError):
    pass


class BridgeForbiddenError(BridgeError):
    pass


class BridgeUnauthorizedError(BridgeError):
    pass


@dataclass(frozen=True, slots=True)
class Discovery:
    port: int
    api: str
    token: str
    pid: int | None = None

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}{self.api}"

    @property
    def ws_url(self) -> str:
        return f"ws://127.0.0.1:{self.port}{self.api}"


def discover(home: Path | None = None) -> Discovery:
    home = home or bridge_home()
    try:
        info = json.loads((home / "bridge.json").read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise BridgeNotRunningError(f"{NOT_RUNNING} (no bridge.json in {home}).") from None
    except (OSError, ValueError) as exc:
        raise BridgeNotRunningError(f"{NOT_RUNNING} (bridge.json unreadable: {exc}).") from None
    if not isinstance(info, dict) or not isinstance(info.get("port"), int):
        raise BridgeNotRunningError(f"{NOT_RUNNING} (bridge.json has no port).")
    try:
        token = (home / "token").read_text(encoding="utf-8").strip()
    except OSError:
        raise BridgeNotRunningError(f"{NOT_RUNNING} (no token file in {home}).") from None
    api = str(info.get("api") or "/api/bridge/v1").rstrip("/")
    pid = info.get("pid")
    return Discovery(int(info["port"]), api, token, pid if isinstance(pid, int) else None)


def error_from_response(status: int, body: Any) -> BridgeError:
    data = body if isinstance(body, dict) else {}
    code = str(data.get("error") or "")
    message = str(data.get("message") or "")
    required = data.get("required")
    if status == 423 or code == "locked_by_operator":
        return BridgeLockedError(LOCKED_MESSAGE, status=status, code="locked_by_operator")
    if status == 401:
        return BridgeUnauthorizedError(
            "The bridge rejected the MCP token. UGS writes a new token each launch;"
            " if this persists, restart the MCP server.",
            status=status,
            code=code or "unauthorized",
        )
    if status == 403:
        level = str(required or "a higher")
        return BridgeForbiddenError(
            f"This needs {level} authority. Only the operator can grant it, in the UGS bridge"
            " panel; use request_authority to ask." + (f" ({message})" if message else ""),
            status=status,
            code=code or "authority_required",
            required=str(required) if required else None,
        )
    if status == 404:
        return BridgeError(message or "Not found.", status=status, code=code or "not_found")
    if status == 409:
        return BridgeError(
            f"The bridge refused: {message or code} ({code}).", status=status, code=code
        )
    return BridgeError(f"Bridge error {status} {code}: {message}".strip(), status=status, code=code)


class BridgeClient:
    def __init__(
        self,
        home: Path | None = None,
        *,
        timeout: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._home = home
        self._timeout = timeout
        self._transport = transport

    @property
    def home(self) -> Path:
        return self._home or bridge_home()

    def discovery(self) -> Discovery:
        return discover(self.home)

    async def request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        body: Any = None,
        timeout: float | None = None,
    ) -> Any:
        d = self.discovery()
        headers = {"Authorization": f"Bearer {d.token}"}
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        try:
            async with httpx.AsyncClient(
                timeout=timeout or self._timeout, transport=self._transport
            ) as http:
                resp = await http.request(
                    method, d.base_url + path, params=clean, json=body, headers=headers
                )
        except httpx.ConnectError:
            raise BridgeNotRunningError(
                f"{NOT_RUNNING} (nothing answers on 127.0.0.1:{d.port}). Start UGS with the"
                " bridge plugin."
            ) from None
        except httpx.TimeoutException:
            raise BridgeError(f"The bridge on 127.0.0.1:{d.port} didn't answer in time.") from None
        except httpx.HTTPError as exc:
            raise BridgeError(f"Couldn't talk to the bridge: {exc}") from None
        try:
            payload: Any = resp.json() if resp.content else None
        except ValueError:
            payload = None
        if resp.status_code >= 400:
            raise error_from_response(resp.status_code, payload)
        return payload

    async def get(self, path: str, **params: Any) -> Any:
        return await self.request("GET", path, params=params)

    async def post(self, path: str, body: Any) -> Any:
        return await self.request("POST", path, body=body)

    # Read endpoints -----------------------------------------------------------------------

    async def status(self) -> dict[str, Any]:
        return dict(await self.get("/status"))

    async def log(
        self, since: int = 0, kinds: Sequence[str] | None = None, limit: int | None = None
    ) -> dict[str, Any]:
        return dict(
            await self.get(
                "/log", since=since, kinds=",".join(kinds) if kinds else None, limit=limit
            )
        )

    async def console(self, since: int = 0, limit: int | None = None) -> dict[str, Any]:
        return dict(await self.get("/console", since=since, limit=limit))

    async def settings(self) -> dict[str, Any]:
        return dict(await self.get("/settings"))

    async def file(self) -> dict[str, Any] | None:
        try:
            return dict(await self.get("/file"))
        except BridgeError as exc:
            if exc.status == 404:
                return None
            raise

    async def file_estimate(self, lines: bool = False) -> dict[str, Any] | None:
        """``GET /file/estimate``: the planner time estimate of the loaded file (None if none)."""
        try:
            if lines:
                return dict(await self.get("/file/estimate"))
            return dict(await self.get("/file/estimate", lines=0))
        except BridgeError as exc:
            if exc.status in (404, 503):
                return None
            raise

    async def profile(self) -> dict[str, Any]:
        return dict(await self.get("/profile"))

    async def command_result(self, command_id: str) -> dict[str, Any]:
        return dict(await self.get(f"/command/{command_id}"))

    # Actions ------------------------------------------------------------------------------

    async def command(
        self,
        lines: Sequence[str],
        *,
        tag: str | None = None,
        expect: str | None = None,
        timeout_s: float | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"lines": list(lines)}
        if tag:
            body["tag"] = tag
        if expect:
            body["expect"] = expect
        if timeout_s:
            body["timeout_s"] = timeout_s
        return dict(await self.post("/command", body))

    async def wait_command(
        self, command_id: str, timeout: float = 10.0, poll: float = 0.05
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while True:
            result = await self.command_result(command_id)
            if result.get("state") in TERMINAL_COMMAND_STATES or time.monotonic() >= deadline:
                return result
            await anyio.sleep(poll)

    async def notify(self, text: str, level: str = "info") -> Any:
        return await self.post("/notify", {"text": text, "level": level})

    async def request_authority(self, level: str, reason: str) -> Any:
        return await self.post("/authority/request", {"level": level, "reason": reason})

    async def realtime(self, op: str) -> Any:
        return await self.post("/realtime", {"op": op})

    async def origins(self) -> dict[str, Any]:
        return dict(await self.get("/origins"))

    async def apply_origin(self, origin_id: str) -> dict[str, Any]:
        return dict(await self.post("/origins/apply", {"origin_id": origin_id}))

    async def job_state(self) -> dict[str, Any] | None:
        try:
            return dict(await self.get("/job/state"))
        except BridgeError as exc:
            if exc.status == 404:
                return None
            raise

    async def resume_plan(self, target: dict[str, Any]) -> dict[str, Any]:
        return dict(await self.post("/job/resume/plan", {"target": target}))

    async def resume(self, plan_id: str) -> dict[str, Any]:
        return dict(await self.post("/job/resume", {"plan_id": plan_id}))

    async def file_load(self, path: str) -> dict[str, Any]:
        return dict(await self.post("/file/load", {"path": path}))

    async def file_run(self) -> dict[str, Any]:
        return dict(await self.post("/file/run", {}))

    # Events -------------------------------------------------------------------------------

    async def events(
        self, since: int = 0, kinds: Sequence[str] | None = None
    ) -> AsyncIterator[dict[str, Any]]:
        """Yield records from ``WS /events`` until the server closes the socket."""
        d = self.discovery()
        url = f"{d.ws_url}/events?since={since}"
        if kinds:
            url += "&kinds=" + ",".join(kinds)
        try:
            async with ws_connect(
                url, additional_headers={"Authorization": f"Bearer {d.token}"}
            ) as ws:
                async for frame in ws:
                    yield json.loads(frame)
        except InvalidStatus as exc:
            status = exc.response.status_code
            try:
                body = json.loads(exc.response.body or b"null")
            except ValueError:
                body = None
            raise error_from_response(status, body) from None
        except ConnectionClosed as exc:
            code = exc.rcvd.code if exc.rcvd is not None else None
            if code in (4401, 4423):
                # Close codes 44xx mirror the HTTP status (locked: 4423, bad token: 4401).
                raise error_from_response(code - 4000, None) from None
            raise BridgeError(f"The bridge closed the event stream ({exc}).") from None
        except (OSError, WebSocketException) as exc:
            raise BridgeNotRunningError(
                f"{NOT_RUNNING} (event stream on 127.0.0.1:{d.port}: {exc})."
            ) from None

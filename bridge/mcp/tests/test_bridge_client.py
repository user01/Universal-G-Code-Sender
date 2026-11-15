from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import anyio
import httpx
import pytest

from ugs_mcp.bridge_client import (
    LOCKED_MESSAGE,
    BridgeClient,
    BridgeError,
    BridgeForbiddenError,
    BridgeLockedError,
    BridgeNotRunningError,
    BridgeUnauthorizedError,
    discover,
)
from ugs_mcp.testing.fake_bridge import FakeBridge

pytestmark = pytest.mark.anyio


def _write_discovery(home: Path, port: int = 1, token: str = "t") -> None:
    (home / "bridge.json").write_text(
        json.dumps({"format": "ugs-bridge-discovery/1", "port": port, "api": "/api/bridge/v1"})
    )
    (home / "token").write_text(token)


def _mock(status: int, body: dict[str, Any], home: Path) -> BridgeClient:
    _write_discovery(home)

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["authorization"] == "Bearer t"
        assert request.url.host == "127.0.0.1"
        return httpx.Response(status, json=body)

    return BridgeClient(home, transport=httpx.MockTransport(handler))


async def test_no_discovery_file(home: Path) -> None:
    with pytest.raises(BridgeNotRunningError, match="isn't running"):
        await BridgeClient(home).status()


async def test_connection_refused_after_stop(home: Path) -> None:
    fake = FakeBridge(home).start()
    client = BridgeClient(home)
    assert (await client.status())["connection"]["connected"]
    fake.stop()
    with pytest.raises(BridgeNotRunningError, match="UGS bridge isn't running"):
        await client.status()


async def test_locked_maps_to_operator_message(home: Path) -> None:
    client = _mock(423, {"error": "locked_by_operator", "message": "x"}, home)
    with pytest.raises(BridgeLockedError) as exc:
        await client.status()
    assert exc.value.message == LOCKED_MESSAGE


async def test_forbidden_names_level(home: Path) -> None:
    client = _mock(
        403, {"error": "authority_required", "message": "no", "required": "motion"}, home
    )
    with pytest.raises(BridgeForbiddenError, match="needs motion authority") as exc:
        await client.command(["G0 X1"])
    assert exc.value.required == "motion"


async def test_unauthorized_and_conflict(home: Path) -> None:
    with pytest.raises(BridgeUnauthorizedError, match="token"):
        await _mock(401, {"error": "unauthorized"}, home).status()
    with pytest.raises(BridgeError, match="not_connected") as exc:
        await _mock(409, {"error": "not_connected", "message": "nope"}, home).command(["$$"])
    assert exc.value.status == 409


async def test_discovery_rereads_token(home: Path) -> None:
    _write_discovery(home, port=9999, token="abc")
    d = discover(home)
    assert (d.port, d.token, d.base_url) == (9999, "abc", "http://127.0.0.1:9999/api/bridge/v1")


async def test_against_fake(fake: FakeBridge, home: Path) -> None:
    client = BridgeClient(home)
    st = await client.status()
    assert st["authority"] == {"locked": False, "level": "query", "motion_expires_at": None}
    assert st["controller"]["state"] == "Alarm"  # the controller comes up locked
    accepted = await client.command(["$I"], tag="t")
    result = await client.wait_command(accepted["id"])
    assert result["state"] == "done"
    assert result["lines"][0]["response"][-1] == "ok"
    with pytest.raises(BridgeForbiddenError, match="motion"):
        await client.command(["$$", "G0 X1"])
    with pytest.raises(BridgeError, match=r"Not found|no command"):
        await client.command_result("c-999")
    assert await client.file() is None
    fake.set_locked(True)
    with pytest.raises(BridgeLockedError):
        await client.status()
    fake.set_locked(False)
    assert (await client.status())["authority"]["locked"] is False


async def test_wrong_token(fake: FakeBridge, home: Path) -> None:
    (home / "token").write_text("wrong")
    with pytest.raises(BridgeUnauthorizedError):
        await BridgeClient(home).status()
    assert fake.records[-1]["kind"] == "client"
    assert fake.records[-1]["data"]["action"] == "auth_failed"


async def test_events_backlog_live_and_lock(
    fake: FakeBridge, home: Path, validate: Callable[[str, Any], None]
) -> None:
    client = BridgeClient(home)
    got: list[dict[str, Any]] = []

    async def follow() -> None:
        with anyio.fail_after(10):
            async for rec in client.events(since=0, kinds=["session", "notify"]):
                got.append(rec)
                validate("log-record", rec)
                if len(got) == 2:  # backlog: bridge_start + connect
                    await client.notify("hello", "info")
                if rec["kind"] == "notify":
                    await anyio.to_thread.run_sync(fake.set_locked, True)

    with pytest.raises(BridgeLockedError):
        await follow()
    assert [r["kind"] for r in got] == ["session", "session", "notify"]
    assert got[-1]["data"]["text"] == "hello"
    # A new stream while locked is refused too.
    with pytest.raises(BridgeLockedError):
        async for _ in client.events():
            pass


async def test_events_wrong_token(fake: FakeBridge, home: Path) -> None:
    (home / "token").write_text("wrong")
    with pytest.raises(BridgeUnauthorizedError):
        async for _ in BridgeClient(home).events():
            pass

"""The fake bridge's live-viewer support (api.md "Additions after W1"), used by the viewer's
live integration run (bridge/viewer/test/live/)."""

from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import anyio
import httpx
import pytest
import websockets

from ugs_mcp.testing.fake_bridge import API, FakeBridge

VT = "viewer-secret"


@pytest.fixture
def vfake(home: Path, validate: Callable[[str, Any], None]) -> Iterator[FakeBridge]:
    viewer_dir = home / "view"
    viewer_dir.mkdir()
    (viewer_dir / "index.html").write_text("<!doctype html><title>viewer</title>")
    bridge = FakeBridge(
        home, viewer_token=VT, viewer_dir=viewer_dir, cors_origins=["http://127.0.0.1:1234"]
    )
    with bridge:
        yield bridge
    for rec in bridge.records:
        validate("log-record", rec)


def _url(fake: FakeBridge, path: str) -> str:
    return f"http://127.0.0.1:{fake.port}{path}"


def test_viewer_token_reads_and_file_content(vfake: FakeBridge, tmp_path: Path) -> None:
    viewer = {"Authorization": f"Bearer v.{VT}"}
    with httpx.Client() as c:
        assert c.get(_url(vfake, f"{API}/file"), headers=viewer).status_code == 404
        assert c.get(_url(vfake, f"{API}/file/content"), headers=viewer).status_code == 404
        prog = tmp_path / "part.nc"
        prog.write_text("(---- OP 01 | T02 | face | x ----)\nG0 Z5\n")
        info = vfake.load_file(prog)
        assert info["lines"] == 2
        r = c.get(_url(vfake, f"{API}/file"), headers=viewer)
        assert r.json()["sha256"] == info["sha256"]
        r = c.get(_url(vfake, f"{API}/file/content"), headers=viewer)
        assert r.headers["content-type"].startswith("text/plain")
        assert r.text == prog.read_text()
        assert c.get(_url(vfake, f"{API}/status"), headers=viewer).json()["connection"]["connected"]
        # the viewer token is read-only: no actions
        r = c.post(_url(vfake, f"{API}/command"), headers=viewer, json={"lines": ["?"]})
        assert r.status_code == 401
        # a bare token (without "v.") is not a viewer token
        bare = {"Authorization": f"Bearer {VT}"}
        assert c.get(_url(vfake, f"{API}/status"), headers=bare).status_code == 401
        # the MCP lock doesn't apply to the viewer
        vfake.set_locked(True)
        assert c.get(_url(vfake, f"{API}/status"), headers=viewer).status_code == 200
        mcp = {"Authorization": f"Bearer {vfake.token}"}
        assert (
            c.get(_url(vfake, f"{API}/status"), headers=mcp).status_code == 200
        )  # reads stay open
        assert (
            c.post(_url(vfake, f"{API}/command"), headers=mcp, json={"lines": ["?"]}).status_code
            == 423
        )
        # static viewer files and CORS for a dev page
        assert "viewer" in c.get(_url(vfake, "/view/")).text
        pre = c.options(
            _url(vfake, f"{API}/status"),
            headers={
                "Origin": "http://127.0.0.1:1234",
                "Access-Control-Request-Method": "GET",
                "Access-Control-Request-Headers": "authorization",
            },
        )
        assert pre.headers["access-control-allow-origin"] == "http://127.0.0.1:1234"
    assert any(r["kind"] == "file" and r["data"]["action"] == "loaded" for r in vfake.records)


@pytest.mark.anyio
async def test_viewer_websocket_vt_survives_lock(vfake: FakeBridge) -> None:
    base = f"ws://127.0.0.1:{vfake.port}{API}/events"
    got: list[dict[str, Any]] = []
    with anyio.fail_after(10):
        async with websockets.connect(f"{base}?since=0&vt={VT}") as ws:
            for _ in range(2):  # backlog: bridge_start, connect
                got.append(json.loads(await ws.recv()))
            await anyio.to_thread.run_sync(vfake.set_locked, True)
            while True:
                rec = json.loads(await ws.recv())
                if rec["kind"] == "authority":
                    break
            assert rec["data"]["locked"] is True
            vfake.emit("notify", "bridge", {"trigger": "t", "text": "still here", "sinks": {}})
            while (rec := json.loads(await ws.recv()))["kind"] != "notify":
                pass
            assert rec["data"]["text"] == "still here"
        # the subprotocol form
        async with websockets.connect(
            f"{base}?since=0",
            subprotocols=["ugs-bridge.v1", f"vt.{VT}"],  # type: ignore[list-item]
        ) as ws:
            assert ws.subprotocol == "ugs-bridge.v1"
            assert json.loads(await ws.recv())["seq"] == 1
    assert [r["kind"] for r in got] == ["session", "session"]
    assert any(
        r["kind"] == "client"
        and r["data"]["client"] == "viewer"
        and r["data"]["action"] == "connect"
        for r in vfake.records
    )


@pytest.mark.anyio
async def test_viewer_websocket_wrong_vt(vfake: FakeBridge) -> None:
    with anyio.fail_after(10), pytest.raises(websockets.InvalidStatus) as e:
        async with websockets.connect(f"ws://127.0.0.1:{vfake.port}{API}/events?vt=nope"):
            pass
    assert e.value.response.status_code == 401

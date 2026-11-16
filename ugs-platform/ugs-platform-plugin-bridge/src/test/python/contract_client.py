"""Cross-language contract check: the MCP server's BridgeClient against the real Java bridge server.

Started by ContractPythonTest (JUnit) with the bridge home of an in-process Java API (temp dir, unlocked at
Query). Prints ``LOCK_ME`` once the event stream is live and waits for ``locked`` on stdin, after which the Java
side has locked Claude access. Exits 0 and prints ``CONTRACT OK`` when every check passes.

    uv run --frozen --package ugs-mcp python contract_client.py <bridge-home>
"""

from __future__ import annotations

import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any

import anyio

from ugs_mcp.bridge_client import (
    BridgeClient,
    BridgeForbiddenError,
    BridgeLockedError,
    BridgeUnauthorizedError,
)

STATUS_KEYS = {
    "seq",
    "connection",
    "controller",
    "homed_this_connection",
    "authority",
    "stream",
}


def check(cond: bool, what: str) -> None:
    if not cond:
        print(f"FAIL {what}", flush=True)
        raise SystemExit(1)
    print(f"ok   {what}", flush=True)


async def main(home: Path) -> None:
    client = BridgeClient(home)

    # status: the BridgeStatus shape
    st = await client.status()
    check(STATUS_KEYS <= st.keys(), "status has the BridgeStatus keys")
    check(st["connection"]["connected"] is True, "status: connected")
    check(st["authority"] == {**st["authority"], "locked": False, "level": "query"}, "status: unlocked at query")
    check(st["controller"]["state"] == "Idle", "status: controller Idle")

    # query: $$ through the queue, then /settings
    accepted = await client.command(["$$"], tag="contract")
    check(accepted["id"].startswith("c-"), "command accepted with an id")
    result = await client.wait_command(accepted["id"], timeout=10)
    check(result["state"] == "done", f"$$ done ({result['state']})")
    check(result["lines"][0]["class"] == "query", "$$ classed as query")
    settings = await client.settings()
    check(settings["settings"]["$130"]["value"] == 445.0, "settings $130 from the last $$")
    q = await client.command(["?"])
    qr = await client.wait_command(q["id"], timeout=10)
    check(qr["lines"][0]["response"][0].startswith("<Idle"), "? answered with a status report")

    # 403: Motion needs the operator
    try:
        await client.command(["G91 G0 Z5", "G90"])
        check(False, "motion refused at query")
    except BridgeForbiddenError as exc:
        check(exc.status == 403 and exc.required == "motion", "403 authority_required, required=motion")

    # 401: wrong token
    with tempfile.TemporaryDirectory() as other:
        shutil.copy(home / "bridge.json", Path(other) / "bridge.json")
        (Path(other) / "token").write_text("wrong\n")
        try:
            await BridgeClient(Path(other)).status()
            check(False, "wrong token refused")
        except BridgeUnauthorizedError as exc:
            check(exc.status == 401, "401 with a wrong token")

    # events: backlog then live; the operator locks -> close 4423 -> BridgeLockedError
    received: list[dict[str, Any]] = []
    outcome: dict[str, BaseException | None] = {"error": None}
    backlog_seen = anyio.Event()

    async def consume() -> None:
        try:
            async for rec in client.events(since=0):
                received.append(rec)
                if len(received) >= 3:
                    backlog_seen.set()
        except BaseException as exc:  # noqa: BLE001 - we inspect it below
            outcome["error"] = exc
            backlog_seen.set()

    async with anyio.create_task_group() as tg:
        tg.start_soon(consume)
        with anyio.fail_after(10):
            await backlog_seen.wait()
        seqs = [r["seq"] for r in received]
        check(seqs[0] == 1 and seqs == sorted(seqs), "events: backlog from seq 1, increasing")
        check(all({"seq", "t", "kind", "src", "data"} <= r.keys() for r in received), "events: record envelope")
        print("LOCK_ME", flush=True)
        answer = await anyio.to_thread.run_sync(sys.stdin.readline)
        check(answer.strip() == "locked", "operator locked")
        with anyio.fail_after(10):
            while outcome["error"] is None:
                await anyio.sleep(0.05)
    check(isinstance(outcome["error"], BridgeLockedError), f"events closed as locked ({outcome['error']!r})")

    # 423 on every request while locked
    try:
        await client.status()
        check(False, "status refused while locked")
    except BridgeLockedError as exc:
        check(exc.status == 423 and exc.code == "locked_by_operator", "423 locked_by_operator")
    try:
        async for _ in client.events(since=0):
            check(False, "events refused while locked")
    except BridgeLockedError:
        check(True, "events upgrade refused while locked")

    print("CONTRACT OK", flush=True)


if __name__ == "__main__":
    anyio.run(main, Path(sys.argv[1]))

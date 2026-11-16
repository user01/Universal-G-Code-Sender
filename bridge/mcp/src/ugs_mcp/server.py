"""ugs-mcp: Claude's side of ugs-bridge (bridge/SPEC.md §5), over stdio.

Observe and Query tools and the offline file tools live here (work package C); the motion,
origin, resume and stream tools with their safety checks are in :mod:`ugs_mcp.motion_tools`
(work package H).
"""

from __future__ import annotations

import re
from collections.abc import Awaitable, Sequence
from pathlib import Path
from typing import Any, TypeVar

import anyio
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from ugs_mcp.bridge_client import BridgeClient, BridgeError
from ugs_mcp.config import local_profile
from ugs_mcp.describe import ERRORS, record_line, settings_text, status_text
from ugs_mcp.gcode.analyze import analyze_path
from ugs_mcp.gcode.prepare import PrepareError, prepare
from ugs_mcp.motion_tools import register_motion_tools
from ugs_mcp.safety import QUERY_LINES, normalize

T = TypeVar("T")

LOG_KINDS = (
    "session",
    "tx",
    "rx",
    "status",
    "message",
    "event",
    "command",
    "file",
    "authority",
    "client",
    "notify",
    "origin",
    "tool",
    "job",
)
MAX_SCAN = 20000
PAGE = 2000

INSTRUCTIONS = """\
Tools for a Shapeoko 4 (GRBL 1.1) run by Universal Gcode Sender with the ugs-bridge plugin.
The operator controls what you may do from the UGS bridge panel. You can always read status,
console, logs, settings and the loaded file, even while UGS access is locked; the lock refuses
every action (sends, motion, hold/reset, notify). Unlocked, Query lets you also send ?, $$, $#,
$G, $I, $N. Motion and streaming need the operator to arm them (ask with request_authority).
Motion tools (move, jog, probe, send, set_work_offset, apply_origin) check every line before
sending: the machine must be homed and Idle, targets inside the profile envelope (machine
coordinates), probes relative and at most 25 mm with the probe input clear, and the call must
leave G21 G90 G94 G17 active. M6, spindle start (M3/M4, non-zero S) and $ setting writes are
always refused: the operator starts the router, and tool changes and $H/$X are confirmed by the
operator in UGS. You may switch the router off with send(['M5']). hold, resume and reset always work
while unlocked. Every motion call states its expected outcome (expect), which is logged.
plan_resume shows a resume plan; start_resume and run_file need Stream authority and the
operator's confirmation. analyze_file and prepare_file work on local G-code files without
UGS. prepare_file never edits the original; it writes a sibling copy and returns a diff."""


async def _guard[T](aw: Awaitable[T]) -> T:
    try:
        return await aw
    except BridgeError as exc:
        raise ToolError(exc.message) from None


def build_server(client: BridgeClient | None = None) -> MCPServer:
    bridge = client or BridgeClient()
    mcp = MCPServer("ugs", instructions=INSTRUCTIONS)

    async def active_profile() -> tuple[dict[str, Any] | None, str | None]:
        try:
            return await bridge.profile(), "the bridge's active profile"
        except BridgeError:
            return local_profile()

    @mcp.tool()
    async def status() -> str:
        """Controller and bridge state in plain words: state, positions, work offset, inputs
        ("probe input active"), alarm, Claude's authority/lock, and the stream."""
        return status_text(await _guard(bridge.status()))

    @mcp.tool()
    async def console(since: int = 0, limit: int = 200) -> str:
        """UGS console lines (messages, sent and received lines, command results) after log
        sequence number `since`. The reply ends with the `since` value for the next call."""
        data = await _guard(bridge.console(since=since, limit=max(1, min(limit, 5000))))
        records = data.get("records") or []
        lines = [record_line(r) for r in records]
        nxt = data.get("next", since)
        if not lines:
            return f"No console lines after #{since}. next since={nxt}"
        return "\n".join([*lines, f"next since={nxt}"])

    @mcp.tool()
    async def log_query(
        kinds: list[str] | None = None,
        since: int = 0,
        grep: str | None = None,
        limit: int = 200,
    ) -> str:
        """Search the session log. `kinds` filters record kinds (session, tx, rx, status,
        message, event, command, file, authority, client, notify, origin, tool, job);
        `grep` is a case-insensitive regular expression matched against each record's data.
        Returns at most `limit` matching records after sequence number `since`."""
        if kinds:
            bad = [k for k in kinds if k not in LOG_KINDS]
            if bad:
                raise ToolError(f"unknown kinds {bad}; valid: {', '.join(LOG_KINDS)}")
        try:
            pattern = re.compile(grep, re.I) if grep else None
        except re.error as exc:
            raise ToolError(f"grep is not a valid regular expression: {exc}") from None
        limit = max(1, min(limit, 2000))
        found: list[str] = []
        cursor, scanned = since, 0
        page_size = PAGE if pattern else limit
        while len(found) < limit and scanned < MAX_SCAN:
            page = await _guard(bridge.log(since=cursor, kinds=kinds, limit=page_size))
            records = page.get("records") or []
            for r in records:
                scanned += 1
                cursor = max(cursor, int(r.get("seq", cursor)))
                line = record_line(r)
                if pattern is None or pattern.search(line):
                    found.append(line)
                    if len(found) >= limit:
                        break
            if len(records) < page_size:
                break
        head = f"{len(found)} record(s)" + (f" matching /{grep}/" if grep else "")
        return "\n".join([head, *found, f"next since={cursor}"])

    @mcp.tool()
    async def settings() -> str:
        """The controller's GRBL $$ settings with names and units (from the last $$ UGS saw),
        flagging values that disagree with the machine profile's expected settings."""
        data = await _guard(bridge.settings())
        profile, source = await active_profile()
        return settings_text(data, profile, source)

    @mcp.tool()
    async def query(cmd: str) -> str:
        """Send one non-motion query to the controller: ?, $$, $#, $G, $I or $N. Needs Query
        authority. Returns the controller's reply lines."""
        norm = normalize(cmd)
        if norm not in QUERY_LINES:
            raise ToolError(
                f"query only sends ?, $$, $#, $G, $I or $N (got {cmd!r}). Setting writes are"
                " refused; motion needs the motion tools and Motion authority."
            )
        accepted = await _guard(bridge.command([norm], tag="query", expect=f"reply to {norm}"))
        result = await _guard(bridge.wait_command(str(accepted["id"]), timeout=10.0))
        state = result.get("state")
        out: list[str] = []
        for ln in result.get("lines") or []:
            out.extend(str(x) for x in ln.get("response") or [])
            if ln.get("error_code") is not None:
                code = int(ln["error_code"])
                out.append(f"(error:{code}: {ERRORS.get(code, 'unknown error')})")
        if state != "done":
            out.append(f"(command {accepted['id']} is {state})")
        return "\n".join(out) if out else f"No reply (command {accepted['id']} {state})."

    @mcp.tool()
    async def notify(text: str, level: str = "info") -> str:
        """Send the operator a notification (Telegram/Slack per notify.json, and the UGS
        panel). level: info, warn or alert."""
        if level not in ("info", "warn", "alert"):
            raise ToolError("level must be info, warn or alert")
        if not text.strip():
            raise ToolError("text is empty")
        await _guard(bridge.notify(text, level))
        return f"Notification queued ({level})."

    @mcp.tool()
    async def request_authority(level: str, reason: str) -> str:
        """Ask the operator for motion or stream authority, with a reason they will see.
        This only asks: nothing is granted until the operator arms it in UGS."""
        if level not in ("motion", "stream"):
            raise ToolError("level must be motion or stream")
        if not reason.strip():
            raise ToolError("give the operator a reason")
        await _guard(bridge.request_authority(level, reason))
        return (
            f"Asked the operator for {level} authority. Nothing changes until they arm it in the"
            " UGS bridge panel; check with status."
        )

    @mcp.tool()
    async def analyze_file(path: str, use_live_offset: bool = True) -> dict[str, Any]:
        """Analyze a G-code file offline: job data (from <file>.job.json or the post
        headers), tools, tool changes, operations with line ranges, extents, feeds,
        unsupported commands, estimated time, and warnings against the machine envelope,
        stock and spoilboard. With use_live_offset and a reachable bridge, the current work
        offset is used to check the envelope in machine coordinates."""
        p = Path(path).expanduser()
        if not p.is_file():
            raise ToolError(f"{p} is not a readable file")
        profile, source = await active_profile()
        wco: Sequence[float] | None = None
        if use_live_offset:
            try:
                st = await bridge.status()
                ctrl = st.get("controller") or {}
                if ctrl.get("wco") and st.get("homed_this_connection"):
                    wco = [float(v) for v in ctrl["wco"]]
            except BridgeError:
                wco = None
        result = await anyio.to_thread.run_sync(lambda: analyze_path(p, profile=profile, wco=wco))
        result["profile"] = source
        result["work_offset_used"] = list(wco) if wco else None
        return {"summary": result.pop("summary"), **result}

    @mcp.tool()
    async def prepare_file(
        path: str,
        edits: list[dict[str, Any]],
        output: str | None = None,
        overwrite: bool = False,
    ) -> dict[str, Any]:
        """Write an edited copy of a G-code file next to the original (default name
        <stem>.ugs<ext>) and return a unified diff. The original is never modified. Edits
        (line numbers refer to the original): {"op":"remove_m6"}, {"op":"comment_out",
        "lines":[a,b]}, {"op":"comment_out_unsupported"}, {"op":"delete","lines":[a,b]},
        {"op":"replace","line":n,"text":"..."}, {"op":"insert_before"|"insert_after",
        "line":n,"text":"..."}, {"op":"regex","pattern":"...","replacement":"...",
        "lines"?:[a,b]}."""
        try:
            return await anyio.to_thread.run_sync(
                lambda: prepare(
                    Path(path),
                    edits,
                    Path(output) if output else None,
                    overwrite=overwrite,
                )
            )
        except PrepareError as exc:
            raise ToolError(str(exc)) from None
        except OSError as exc:
            raise ToolError(f"couldn't write the copy: {exc}") from None

    register_motion_tools(mcp, bridge, active_profile)
    return mcp


def main() -> None:
    build_server().run()

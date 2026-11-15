# ugs-mcp

Claude's side of ugs-bridge (`bridge/SPEC.md` §5): an MCP server over stdio that talks to the UGS bridge plugin's local API (`bridge/docs/api.md`) and analyzes G-code files offline.

## Register with Claude Code

From anywhere, pointing at this repository's `bridge/` folder (the uv workspace root):

```sh
claude mcp add ugs -- uv run --directory /path/to/ugs-bridge/bridge --package ugs-mcp ugs-mcp
```

`--directory` makes uv use the workspace (and its lock file) regardless of Claude's working directory; `--package ugs-mcp` selects this member. The server finds the bridge through `bridge.json` and `token` in `~/Library/Application Support/ugs-bridge/` (override with `UGS_BRIDGE_HOME`), re-read on every call, so it survives UGS restarts.

Optional environment:

| Variable | Default | Use |
|---|---|---|
| `UGS_BRIDGE_HOME` | `~/Library/Application Support/ugs-bridge` | discovery files (tests point it at a temp dir) |
| `UGS_BRIDGE_PROFILE` | `bridge/profiles/shapeoko4-standard.json` | machine profile used when the bridge isn't reachable (otherwise `GET /profile` wins) |

## Tools (work package C)

| Tool | Authority | What it does |
|---|---|---|
| `status` | Observe | State, MPos/WPos/WCO, inputs in words ("probe input active"), alarm text, Claude's lock/level, stream, tool, interceptor |
| `console(since, limit)` | Observe | Console records (messages, `>` sent, `<` received, command results) after `since`; ends with `next since=N` |
| `log_query(kinds, since, grep, limit)` | Observe | Session log filtered by kind and a case-insensitive regex |
| `settings` | Observe | Last `$$` with names and units; flags mismatches with the profile's `expected_settings` |
| `query(cmd)` | Query | One of `?`, `$$`, `$#`, `$G`, `$I`, `$N`; anything else is refused locally |
| `notify(text, level)` | Observe | `POST /notify` (info, warn, alert) |
| `request_authority(level, reason)` | Observe | Asks the operator for motion or stream; grants nothing |
| `analyze_file(path, use_live_offset)` | none | Job data (sidecar → post headers → G-code), tools, tool changes, operations with line ranges, extents, feeds, unsupported commands, time estimate, envelope/stock/spoilboard warnings |
| `prepare_file(path, edits, output, overwrite)` | none | Writes an edited sibling copy (`<stem>.ugs<ext>` by default) and returns a unified diff; never writes the original |

Bridge failures come back as plain tool errors: "UGS access is locked by the operator" (423), "This needs motion authority…" (403), "UGS bridge isn't running" (no discovery file or connection refused).

No motion tools yet: `move`, `jog`, `probe`, `send`, `set_work_offset`, `hold`/`resume`/`reset` and `run_file` belong to package H, which builds on `ugs_mcp.safety` (the authority classification from api.md, already tested).

## Layout

```
src/ugs_mcp/
  server.py          MCPServer (mcp 2.x; FastMCP was renamed MCPServer) and the tools
  bridge_client.py   HTTP + WS client, discovery, error mapping
  describe.py        plain-words status, settings, log lines; GRBL alarm/error texts
  safety.py          line classification (realtime/query/confirm/motion/stream)
  config.py          UGS_BRIDGE_HOME, profile lookup
  gcode/
    lexer.py         comments (nesting-aware), words, $-commands
    interpreter.py   GRBL modal state, moves with arc extents (IJK and R, G17/18/19), G53, tool changes, unsupported codes
    headers.py       post-header grammar (bridge/docs/post-headers.md)
    job.py           sidecar loading, operation segmentation, sidecar-shaped job
    analyze.py       analyze_file
    prepare.py       prepare_file
  testing/
    fake_bridge.py   Starlette/uvicorn stand-in for the Java API, backed by resurfacer's SimulatedGrbl
```

## Development

From `bridge/`:

```sh
uv sync --all-packages
uv run pytest mcp/tests
uv run ruff check mcp && uv run ruff format --check mcp
uv run mypy
```

Tests that read the operator's real files skip when `bridge/testdata/nc/` is absent. The fake bridge binds `127.0.0.1` on an ephemeral port, writes its discovery files to a temp `UGS_BRIDGE_HOME` and never opens a serial port.

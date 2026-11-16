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

## Tools

Authority is what the operator must have granted in the UGS bridge panel (SPEC §4.3); the bridge enforces it (403), and the server checks it first so Claude gets a plain answer. When UGS access is locked every tool that talks to the bridge answers "UGS access is locked by the operator".

| Tool | Authority | What it sends / does |
|---|---|---|
| `status` | Observe | `GET /status`: state, MPos/WPos/WCO, inputs in words ("probe input active"), alarm text, Claude's lock/level, stream, tool, interceptor |
| `console(since, limit)` | Observe | Console records (messages, `>` sent, `<` received, command results) after `since`; ends with `next since=N` |
| `log_query(kinds, since, grep, limit)` | Observe | Session log filtered by kind and a case-insensitive regex |
| `settings` | Observe | Last `$$` with names and units; flags mismatches with the profile's `expected_settings` |
| `query(cmd)` | Query | One of `?`, `$$`, `$#`, `$G`, `$I`, `$N`; anything else is refused locally |
| `notify(text, level)` | Observe | `POST /notify` (info, warn, alert) |
| `request_authority(level, reason)` | Observe | Asks the operator for motion or stream; grants nothing |
| `move(x?, y?, z?, machine=true, feed?, expect?)` | Motion | `G21 G90 G94 [G53] G0 X.. Y.. Z..` (or `G1 … F`), then waits for Idle |
| `jog(axis, distance, feed, expect?)` | Motion | `$J=G21 G91 <axis><d> F<f>`, at most 100 mm, waits for Idle |
| `probe(axis, max_distance, feed, retract=2, expect?)` | Motion | `G21 G91 G38.2 Z-<d> F<f>`, `G0 Z<retract>`, `G90`; returns `[PRB]` in machine and work coordinates and the BitZero offset it implies. Z only until the profile has `bitzero.bore_diameter` (ledger B3) |
| `send(lines, expect, timeout_s?)` | Motion | The lines as given, after every check below; `$H`/`$X` alone wait for the operator |
| `set_work_offset(x?, y?, z?, expect?)` | Motion | `G21 G10 L2 P1 …` then `$#`; verifies G54 within 0.01 mm. Values are machine coordinates of the work zero |
| `hold`, `resume`, `reset` | always (unlocked) | `POST /realtime` `!`, `~`, `0x18` |
| `origins(compare=true)` | Observe | `GET /origins`, each compared with the current WCO |
| `apply_origin(origin_id)` | Motion | `POST /origins/apply` (the bridge sends `G10 L2 P1` + `$#`); verifies within 0.01 mm |
| `job_state` | Observe | `GET /job/state`, readable |
| `plan_resume(target)` | Observe | `POST /job/resume/plan` (`line`, `operation`/`OP n`, or `stopped`); prints requires, checks, preamble, warnings, plan id; the preamble is also checked against the envelope |
| `start_resume(plan_id)` | Stream + operator | `POST /job/resume`; nothing runs until the operator confirms in UGS |
| `run_file(path)` | Stream + operator | Analyzes the file, `POST /file/load`, `POST /file/run`; the operator confirms name and SHA-256 in UGS |
| `analyze_file(path, use_live_offset)` | none | Job data (sidecar → post headers → G-code), tools, tool changes, operations with line ranges, extents, feeds, unsupported commands, time estimate, envelope/stock/spoilboard warnings |
| `prepare_file(path, edits, output, overwrite)` | none | Writes an edited sibling copy (`<stem>.ugs<ext>` by default) and returns a unified diff; never writes the original |

### Safety checks (SPEC §5.2, `ugs_mcp.safety.check_motion`)

Every motion tool runs the exact lines it will send through the checker, with the starting modal state read from `$G`, and sends nothing if any rule fails. Each refusal says what to do instead.

| Rule | Refuses | Relies on |
|---|---|---|
| `authority` / `connection` | anything above Query without Motion, or locked / disconnected | bridge status |
| `state`, `busy` | motion unless the controller is Idle (alarm, hold, run, door), a stream is running or the tool-change interceptor is active | bridge status |
| `homed` | motion or `G10` before `$H` in this connection | `homed_this_connection` (connecting resets the controller, ledger A5) |
| `envelope` | any target, probe end or arc box outside `envelope_mm` (machine coordinates, never `$130`–`$132`); work zeros outside it | profile envelope, **unverified** (ledger A1/C6) |
| `rapid_below_safe_z` | `G0` moves in X/Y while the spindle is below `safe_z_machine` | profile safe Z (assumed) |
| `modal_restore` | ending the call in G91, G20, G93 or G18/G19 | `$G` at the start |
| `probe_*` | absolute probes, more than 25 mm, upward Z, several axes, X/Y without a bore, no or too fast feed, input active (`Pn:P`) or unknown, two probes in one call | profile `bitzero`/`bitsetter` feeds; pin letter `P` (ledger A6) |
| `tool_change`, `spindle`, `program_flow` | `M6`, `M3`/`M4`, `M0`/`M1`/`M2`/`M30` | |
| `setting_write`, `system_cmd` | `$n=`, `$Nn=`, `$RST`, `$SLP`, `$C` … | |
| `unsupported`, `syntax`, `feed_limit` | what GRBL 1.1 rejects (interpreter tables), `G28`/`G30`/`G92`/`G43.1`/`G55`–`G59`/`G38.4`/`G38.5`, motion without feed, feeds above `rapid_mm_min` | |
| `confirm_alone`, `expect`, `line_count` | `$H`/`$X` with other lines, no stated outcome, more than 50 lines | |

Positions are tracked per axis as an interval: after a probe the contact can be anywhere between start and target, so a following relative move is checked at both ends. If a call fails half way with a non-default mode active, the tool restores `G21 G90 G94 G17` (or, in alarm, sends a soft reset). Results name the unverified profile values the checks used.

## Layout

```
src/ugs_mcp/
  server.py          MCPServer (mcp 2.x; FastMCP was renamed MCPServer) and the tools
  bridge_client.py   HTTP + WS client, discovery, error mapping
  describe.py        plain-words status, settings, log lines; GRBL alarm/error texts
  safety.py          line classification + the SPEC §5.2 motion checker
  motion_tools.py    motion, origin, resume and stream tools (package H)
  config.py          UGS_BRIDGE_HOME, profile lookup
  gcode/
    lexer.py         comments (nesting-aware), words, $-commands
    interpreter.py   GRBL modal state, moves with arc extents (IJK and R, G17/18/19), G53, tool changes, unsupported codes
    headers.py       post-header grammar (bridge/docs/post-headers.md)
    job.py           sidecar loading, operation segmentation, sidecar-shaped job
    analyze.py       analyze_file
    prepare.py       prepare_file
  testing/
    fake_bridge.py   Starlette/uvicorn stand-in for the Java API: command queue, operator dialogs,
                     authority levels, origins, resume plans; backed by resurfacer's SimulatedGrbl
                     or ugs_sim's ShapeokoGrbl (BitSetter, BitZero)
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

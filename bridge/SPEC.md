# UGS Bridge: shared machine control for an operator and Claude

Status: draft v0.1, 2026-11-15. Sibling of `../resurfacer` (Shapeoko Surface).

## 1. Purpose

Let the operator and Claude work the same CNC machine together, with the operator in full control:

1. **Shared control.** The operator keeps the Universal Gcode Sender (UGS) GUI. Claude reads everything UGS sees and can send commands through MCP tools. Every command Claude sends shows up in the UGS console, labeled as Claude's.
2. **Complete logging.** Every byte to and from the controller, every status report, event and file streamed, with timestamps and who started it (operator or Claude). Logs are kept as files that both can read.
3. **3D view.** A live 3D view of the job: toolpaths colored by operation, the stock with material removed, the live tool position, probe hits, fixtures and the machine envelope. It accepts extra job data (stock, tools, zero, operations) beyond the raw G-code.

Non-goals: replacing UGS, writing CAM, controllers other than GRBL 1.1 in v1, remote access over the internet.

## 2. Background (from the 2026-11-15 session)

Working the user's Shapeoko 4 through Carbide Motion and UGS showed:

- **Carbide Motion is opaque.** It rewrites MDI input (a `G91 G38.2 Z-4` ran as an absolute move), keeps no log file, resets G54 to zero on connect (`$x G92.1 G49 G10L2P1X0Y0Z0 G54 G21 G90`) and keeps work zero in its own convention (positive X/Y). It sends `M56P0` after every command.
- **UGS sends commands verbatim**, and its web pendant already offers a REST API (`/api/v1`: status, `machine/sendGcode`, homing, soft reset, files, macros) and a WebSocket (`/ws/v1/events`).
- **The pendant is not enough:**
  - Its status omits the input pins (`Pn:`) and probe results.
  - The console text (`ok`, `error:N`, `[PRB:…]`, `ALARM:N`) isn't available to clients.
  - Action endpoints are plain GETs with no authentication.
  - It listens on every network interface, including the LAN and Tailscale.
  - UGS's `messages.log` doesn't record console traffic.
- **Opening the serial port resets the controller** (DTR), so it powers up locked in alarm and has to be homed again.
- UGS core already exposes what's missing (found in `ugs-core.jar` 2.1.23):
  - `BackendAPIReadOnly.addMessageListener` provides console messages, each tagged `MessageType` (INFO, VERBOSE, ERROR).
  - `UGSEventListener` receives `ControllerStatusEvent` (with `ControllerStatus.getEnabledPins()`), `ProbeEvent` (probe position), `CommandEvent` (sent, completed, skipped), `AlarmEvent`, `StreamEvent` and `FileStateEvent`.
  - `ICommunicatorListener.rawResponseListener` provides raw controller lines.
  - `BackendAPI.sendGcodeCommand`, `probe`, `performHomingCycle`, `killAlarmLock` and `issueSoftReset` cover control.

## 3. Architecture

```
┌──────────────────────── UGS Platform (operator's GUI) ────────────────────────┐
│  console · DRO · jog · visualizer · file streaming                            │
│  ┌─────────────── ugs-bridge plugin (Java, NetBeans module) ───────────────┐  │
│  │ listeners: messages, events, raw responses, commands                    │  │
│  │ session log (JSONL)  ·  authority panel (operator arms/disarms Claude)  │  │
│  │ local API: HTTP + WebSocket on 127.0.0.1, bearer token                  │  │
│  │ 3D viewer: static web app served by the same server                     │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────┬────────────────────────────────────────┘
                                       │ 127.0.0.1 only
┌──────────────────────────────────────┴────────────────────────────────────────┐
│  ugs-mcp (Python, uv, stdio MCP server started by Claude Code)                │
│  tools · safety policy · G-code analysis · log queries · view snapshots       │
└───────────────────────────────────────────────────────────────────────────────┘
```

Split rationale:
- **The Java plugin is kept small**: listeners, logging, the authority switch and a narrow API. Small Java changes are easy to keep up to date with UGS releases.
- **The MCP server, safety policy and G-code analysis are in Python**, where they can share the GRBL parser and simulator from `../resurfacer` and be tested quickly with uv and pytest.
- **The 3D viewer runs in a browser**, so the operator can keep it on a second screen and the MCP server can capture snapshots for Claude.

### 3.1 Plugin packaging

Preferred: a standalone NetBeans module (`.nbm`) installed through UGS's *Tools → Plugins*, built against UGS's published module artifacts, with no fork.

Fallback: a fork of `winder/Universal-G-Code-Sender` (GPLv3) that adds the module and rebases on releases.

Research task R1 decides which.

## 4. ugs-bridge plugin

### 4.1 Capture

Subscribe to:

| Source | Records |
|---|---|
| `MessageListener` | console text and its `MessageType` |
| `ICommunicatorListener.rawResponseListener`, `commandSent`, `commandSkipped`, `communicatorPausedOnError` | raw lines sent and received |
| `ControllerStatusEvent` | state, sub-state, MPos, WPos, WCO, feed, spindle, overrides, **pins**, accessories |
| `ProbeEvent` | probe position (machine coordinates) |
| `CommandEvent` | command lifecycle: queued, sent, done, error, with the originating client |
| `AlarmEvent`, `ControllerStateEvent`, `StreamEvent`, `FileStateEvent` | as named |

### 4.2 Session log

- One JSONL file per connection: `~/Library/Application Support/ugs-bridge/logs/YYYY-MM-DD/HHMMSS-<port>.jsonl`.
- Each record: `{"t": <ISO8601 ms>, "mono": <seconds>, "kind": "tx|rx|status|event|message|file|authority|client", "src": "operator|claude|ugs", "data": …}`.
- Status reports are logged when they change, plus one heartbeat per second.
- Streamed files are logged with their SHA-256 and path; the file contents are copied into the session folder once.
- Logs are never rotated away automatically. Old ones are compressed after 30 days.
- A `ugs-bridge tail` command and the MCP tool `log_query` read the logs.

### 4.3 Authority

The operator controls Claude's authority from a UGS panel (a docked window). Claude can't change it.

**MCP lock (master switch).** A lock/unlock toggle in the UGS toolbar, with a padlock icon that shows the state, mirrored in the bridge panel and the status bar.
- **Locked:** Claude can **always read** (status, console, logs, settings, the loaded file, the event stream), but every action from the MCP side, including feed hold and reset, is refused with `423 Locked` and `{"error": "locked_by_operator"}`, and nothing reaches the machine. (Changed 2026-11-16 at the operator's request: originally reads were refused too.) Session logging, notifications and the 3D viewer keep working.
- **Unlocked:** the authority levels below apply.
- UGS **starts locked** every launch, and the state is never persisted as unlocked. Locking also disarms Motion and Stream immediately: it sends nothing to the machine, but drops any queued Claude commands that haven't been sent yet. Unlocking returns to Observe/Query, never to an armed level.
- Every lock/unlock is logged (`kind: "authority"`) and, if enabled, notified. Keyboard shortcut and menu entry: *Machine → Lock Claude access*.

| Level | Claude may | Default |
|---|---|---|
| **Observe** | read status, console, logs, settings, the loaded file | on |
| **Query** | additionally send non-motion queries: `?`, `$$`, `$#`, `$G`, `$I`, `$N` | on |
| **Motion** | additionally send motion and modal commands, jog, probe, set offsets | operator arms it; expires after 15 min idle and on alarm, disconnect or reset |
| **Stream** | additionally run a file | the operator confirms each file (name and SHA-256) in a dialog |

Always allowed at every level, logged and shown: **feed hold**, **jog cancel** and **soft reset**. Stopping must never need permission.

Two actions always need operator confirmation in the GUI, even when armed: `$H` (homing) and `$X` (unlock).

### 4.4 Local API

- Binds to 127.0.0.1 only, on a port chosen at start (default 8765). Every request needs a bearer token written to `~/Library/Application Support/ugs-bridge/token` with mode 0600.
- Actions are POST only. GET is read-only.
- Endpoints (v1):
  - `GET /status`: the full `ControllerStatus`, including pins and WCO, plus authority, connection and stream state.
  - `GET /console?since=<seq>`: console lines with sequence numbers.
  - `GET /log?since=<seq>&kinds=…`: session log records.
  - `GET /settings`: the last `$$` parsed, plus the raw text.
  - `GET /file`: the loaded file's path, hash and line count.
  - `POST /command` `{lines:[…], tag}`: queue lines. Returns a command id. Results arrive as `CommandEvent`s (ok, error N, or alarm) with the console lines each command produced.
  - `POST /realtime` `{op: hold|resume|jog_cancel|reset}`.
  - `POST /file/load` `{path}`, `POST /file/run`. Running requires Stream authority plus the operator's dialog.
  - `WS /events`: everything in §4.1, pushed live.
- Commands from Claude appear in the UGS console prefixed `[claude]`.

### 4.5 Disable the stock pendant while the bridge runs

Show a warning if the stock web pendant is running: it is unauthenticated and listens on every network interface.

## 5. ugs-mcp server

- Python 3.12 managed with uv. Built on the official `mcp` SDK (FastMCP), stdio transport.
- Registered with `claude mcp add ugs -- uv run --directory /Users/erik/code/ugs-bridge/bridge --package ugs-mcp ugs-mcp` (see `bridge/mcp/README.md`).

### 5.1 Tools

| Tool | Authority | Notes |
|---|---|---|
| `status` | Observe | State, positions, WCO, pins in plain words ("probe input active"), alarm text, authority |
| `console(since)` | Observe | Console lines |
| `log_query(kinds, since, grep)` | Observe | Reads the session log |
| `settings` | Observe | `$$` with names and units; flags mismatches with the machine profile (§7) |
| `query(cmd)` | Query | One of `?`, `$$`, `$#`, `$G`, `$I`, `$N` |
| `move(x?, y?, z?, machine=true, feed?)` | Motion | One straight move. Checked against the envelope. Rapid unless a feed is given. Waits until Idle |
| `jog(axis, distance, feed)` | Motion | `$J=`, bounded |
| `probe(axis, max_distance, feed, retract?)` | Motion | `G38.2`, at most 25 mm, always relative. Restores G90 afterwards. Returns `[PRB]` and the stop position. Z only until the BitZero bore is known (ledger B3) |
| `send(lines, expect)` | Motion | Raw lines after safety checks (§5.2). Returns the reply and console output for each line |
| `set_work_offset(x?, y?, z?)` | Motion | `G10 L2 P1`, then checks it by reading `$#` |
| `hold`, `resume`, `reset` | always | Real-time commands |
| `analyze_file(path)` | none | Extents, tools, operations, feeds, unsupported commands (`M6`), estimated time, warnings against the envelope and stock |
| `prepare_file(path, edits)` | none | Writes a UGS-ready copy (for example with `M6` removed) and shows a diff. Never edits the original |
| `run_file(path)` | Stream | Loads the file; the operator confirms in UGS |
| `origins`, `apply_origin`, `job_state`, `plan_resume`, `start_resume` | see §11.8 | |
| `view(snapshot_of, camera?)` | none | Returns a PNG of the 3D view, so Claude can see what the operator sees |

### 5.2 Safety checks, applied before anything is sent

- Commands are parsed with the GRBL parser from `../resurfacer`. Modal state is tracked, and commands that would leave a dangerous mode are refused. For example, `G91` must be restored by the same tool call.
- The machine must be homed in this connection before any motion.
- Targets must fall inside the machine envelope from the profile, not from `$130`–`$132` alone (on this machine those were wrong).
- Probes must be bounded and start with the probe input clear.
- Commands the controller doesn't support (`M6`, `T` with `M6`) are refused with an explanation.
- Spindle: Claude may switch the router off (`M5`, `S0`) but never on. `M3`/`M4` and a non-zero `S` word are refused; the operator starts the router (D21).
- Each tool call states its expected outcome, which is logged with the result.

## 6. 3D viewer

Built with Three.js, served by the plugin at `http://127.0.0.1:<port>/view`. It works without a connection when opened on a file.

### 6.1 Scene

- **Machine:** envelope box, homing corner, BitSetter, spoilboard plane, work zero (G54) axes.
- **Stock:** a box from the job data (§6.3), positioned from the work zero.
- **Toolpath:** cuts colored by operation, rapids dashed, plunges highlighted. The line under the cursor is shown alongside its G-code and line number.
- **Material removal:** a height-field model of the stock (0.25 mm cells by default) that removes material along the path for flat and ball cutters. It can scrub to any line.
- **Live state:**
  - the tool shown at its real position from status reports, with a trail of the actual path;
  - the planned position at the current streamed line;
  - probe hits as markers with their values.
- **Warnings drawn in red:** cuts below the stock bottom or the spoilboard limit, moves outside the envelope, crossings into fixtures.

### 6.2 Controls

Timeline scrubber by line or estimated time; show and hide each operation; section plane; top, front and iso views. Snapshots export as PNG, and the scene as glTF.

### 6.3 Extra job data (sidecar)

A sidecar file named `<program>.job.json` sits next to the G-code:

```json
{
  "format": "ugs-bridge-job/1",
  "units": "mm",
  "stock": {"size": [240, 95, 19.4], "material": "hard maple", "origin": "front-left-top"},
  "zero": {"x": "stock front-left", "y": "stock front-left", "z": "raw stock top", "tool": "T02"},
  "spoilboard_limit_z": -19.7,
  "tools": [{"id": "T21", "name": "Whiteside RU1600", "type": "flat", "diameter": 3.175, "flute_length": 12.7}],
  "operations": [{"name": "clear_hi", "tool": "T21", "lines": [47, 2083], "z_range": [-9.4, -7.4]}],
  "fixtures": [{"name": "tape", "kind": "keepout", "box": [[0, 0, -19.4], [240, 95, -19.4]]}],
  "tabs": [{"at": [60, 20], "top_z": -16.4}]
}
```

If there's no sidecar, the viewer reads the user's post-processor header comments: `(STOCK: …)`, `(ZERO …)`, `(TOOL: …)`, `(---- OP 01 | T21 | clear_hi | … ----)` and `(CUT EXTENT …)`. These headers are already consistent across the user's files. The post (`post/user.py`) can write the sidecar directly later.

## 7. Machine profile

`profiles/shapeoko4-standard.json` records:
- the measured envelope (445 × 456 × 95 for this Standard);
- the BitSetter location (X −4, Y −449);
- the BitZero plate thickness, once measured;
- spindle control (BitRunner, so `M3`/`M5` only);
- the connect-reset behavior.

The MCP server warns when the controller's `$$` disagrees with the profile. On 2026-11-15, `$130`/`$131` read 845/850 on a Standard.

## 8. Testing

- **Python:** unit tests for the safety checks and G-code analysis. Integration tests run the MCP server against a fake bridge built on the `../resurfacer` GRBL simulator.
- **Java:** run the plugin in UGS connected to the `../resurfacer` simulator through a pseudo-terminal (whether UGS can open one is part of R1), or to the real machine with no motion. Check the log contents, the authority rules, and that every Claude command shows in the console.
- **Hardware:** a checklist like `../resurfacer/docs/hardware-validation.md`, run on scrap wood with the operator at the E-stop.

## 9. Milestones

| M | Deliverable |
|---|---|
| R1 | Research: build UGS 2.1.x from source, and confirm a standalone `.nbm` can register listeners and a panel. Pick plugin or fork. |
| B1 | Plugin capture and JSONL session log, no API. Proves complete logging. |
| B2 | Local API (read-only) and the authority panel. |
| M1 | MCP server with the Observe and Query tools, `analyze_file` and `prepare_file`. |
| B3 | Command API with the Motion level; Claude's commands labeled in the console. |
| M2 | MCP motion tools with the safety checks; hardware checklist. |
| V1 | 3D viewer: toolpath, operations, live tool, probe markers. |
| V2 | Material removal, sidecar files, warnings, `view` snapshots for Claude. |
| S1 | Stream authority with the per-file operator confirmation. |

## 10. Open questions

1. Can a third-party `.nbm` built against UGS release artifacts load in the signed macOS app bundle, or does it need a fork build? (R1)
2. Does `CommandEvent` give each command's `ok`/`error` reliably when commands go through `sendGcodeCommand`, or do responses have to be matched from raw lines?
3. Should the 3D viewer also live in UGS's own visualizer (JOGL) as a panel? The browser is chosen first because it's simpler to build and to capture snapshots from.
4. Can the stopping rules for `hold`/`reset` match Shapeoko Surface's (§14 of its spec), so that a reset after the hold completes keeps position?

## 11. Multi-tool jobs, origins and resume (added 2026-11-15)

Goal: run long multi-tool files (for example `dr_ryan_complete_T02_then_T21.nc`: 26 781 lines, 9 operations, T02 then T21, about 62 min) in one setup with the BitZero and the BitSetter. They must survive a stop, a reset, a UGS crash or a power cut, and resume mid-file without re-zeroing on stock that has already been cut.

### 11.1 What UGS 2.1.30 already has, and the gaps

| UGS feature | What it does | Gap for us |
|---|---|---|
| `ToolChangeInterceptor` (core, #3149) | On `M6`: sends `T`, `G0 Z<file max Z>`, `M5`, then the "change tool" dialog and "continue" | No tool-length measurement, so Z is wrong after a change. Parks only in Z, in work coordinates. |
| `CommandInterceptorService.register()` | Pluggable interceptors | Usable as is: we register our own and turn the stock one off (`handleToolChanges=false`). |
| `RunFromProcessor` / editor "Run from here" | Skips to line N, restores modal state, `G0 Z<max Z seen>`, `G0 XY`, accessories, `G1 Z` | Doesn't know which tool should be in the collet. Plunges at the modal (cutting) feed. The clearance is "max Z seen", not a configured value. No checks on origin or homing. In-memory only. |
| ProbeModule | Z, XYZ-corner, outside and hole-center probing with a plate | Not tied to the BitSetter reference. Plate thickness isn't verified. |

### 11.2 Heights and positions (machine profile + job)

| Name | Source | Example |
|---|---|---|
| `safe_z_machine` | profile | `G53 Z-5` |
| `clearance_z_work` | job (post header `(CLEARANCE: …)`, sidecar, or entered by the operator) | `Z+5` |
| `tool_change_park` | profile, machine XYZ | front center, `Z-5` |
| `bitsetter_xy`, `bitsetter_probe` | profile: position, maximum travel, fast and slow feeds, retract | X −4 Y −449 |
| `bitzero` | profile: plate thickness (measured), corner offsets, probe feeds | |

All moves the bridge generates (tool change, resume, park) go to `safe_z_machine` before any XY move. They check the envelope before sending.

### 11.3 Origins: store and recover

- Every work-offset change is recorded automatically in `origins.json` (Application Support): G54 WCO in machine coordinates, method (`bitzero_xyz`, `bitzero_z`, `manual`, `restored`), the tool, the BitSetter reference (§11.4), time, session and job file. This covers G10, probing, UGS's "zero" buttons and offsets read back from `$#`. Nothing is lost when another sender (for example Carbide Motion) resets G54.
- The operator can name an origin ("dr ryan stock") and pin it to a job file.
- **Recover:** after homing, *apply origin* writes it back with `G10 L2 P1`, reads `$#` to verify (within 0.01 mm), and logs the change.
- On connect, if G54 differs from the job's pinned origin, the panel says so and offers to restore it.

### 11.4 BitZero + BitSetter workflow (Carbide-style tool length)

- **Reference:** after zeroing Z on the stock with the BitZero (tool A), measure tool A on the BitSetter (trigger at machine Z `S_A`). Store `ref = WCO_z − S_A`. This stays constant for any tool as long as the BitSetter, spoilboard and homing don't change.
- **After any tool change:** measure the new tool (`S_B`) and set `WCO_z = S_B + ref` with `G10 L2 P1 Z…`. The new tool cuts to the same stock top without touching the (already cut) stock.
- **Verify:** re-measuring the same tool must reproduce `S` within 0.05 mm, otherwise stop and tell the operator. The reference is invalidated (with a warning) when homing or the profile's BitSetter position changes.
- Each probe uses two passes (fast, then retract and slow), stays bounded by `bitsetter_probe.max_travel`, and first checks that the probe input is clear (`Pn`).

### 11.5 Tool-change interceptor (replaces the stock one when enabled; on by default, D20)

On `M6 Tn`:
0. If `Tn` is the tool in the collet and its length is known from a measurement in this connection (the reference tool, or the last tool measured after a change) with no reset, alarm, homing, reconnect or G54 Z change since, send only `Tn`, log a `tool` record `confirmed` with `measurement_skipped`, and continue: no park, prompt, probe or notification (D22). Any doubt runs the full sequence.
1. `M5`, then `G53 G0 Z<safe>`, then `G53 G0` to `tool_change_park`.
2. Notify (UGS dialog, Telegram/Slack, MCP event) with the tool from the job data: "Change to T21 Whiteside RU1600 1/8 in upcut".
3. The operator confirms in UGS. Claude can't confirm a tool change.
4. Check the probe input is clear, measure on the BitSetter, set `WCO_z = S_new + ref`, and log both values.
5. Return to the **pre-change XY at `G53 Z<safe>`** and continue the stream. The program's own `M3` and moves follow. If the next motion line would start below the clearance height without its own retract, warn.

Without a BitSetter reference the interceptor parks (spindle off, safe Z), says why in the UGS dialog and stops: the stream stays paused (or is cancelled if the operator presses Abort).

At job start, the setup checklist (§11.7) measures the first tool to set `ref`.

### 11.6 Job journal and resume

- **Journal** `<session>/job-<file sha>.state.json`, written atomically at least once a second while streaming:
  - file path and SHA-256;
  - last line acknowledged (`ok`) and last line known complete;
  - current operation and tool;
  - WCO and `ref`;
  - modal state;
  - stream state.
  
  It survives a stop, an alarm, a UGS crash and a power cut.
- **Acknowledged doesn't mean executed.** GRBL replies `ok` when a line enters the planner (up to 15 blocks plus the serial buffer), not when it runs. The resume point is found by matching the last reported machine position to the path segments between the last line known complete and the last acknowledged line. Without a position (power cut), the bridge backs up past every line that might not have run.
- **Safe resume points:** by default resume at the last *safe point* at or before that line, meaning a line where the tool is at or above `clearance_z_work`, or at an operation start. Resuming mid-contour is allowed but needs explicit confirmation, and the viewer shows it.
- **Resume plan** (shown as text and in the viewer, confirmed by the operator before it runs):
  1. Check that the machine is homed in this connection and that WCO matches the journal's origin. If not, offer *recover origin* (§11.3).
  2. Check that the correct tool for the resume line is in the collet. If the tool may have changed or the length is unknown (power cut, different tool), run the tool-change interceptor sequence, including the BitSetter measurement.
  3. Retract first with `G21 G53 G0 Z<safe>` (it carries its own units, so it can't be read as inches), then restore the modal state (units, distance mode, plane, WCS, feed mode).
  4. `G0 X Y` to the resume point, then `G0 Z<clearance_z_work>`.
  5. `M3`, then `G4 P<spin-up>` (profile; the BitRunner switches power and the operator sets RPM).
  6. `G1 Z<target>` at the operation's **plunge feed**, then the modal cutting feed for the rest.
  7. Stream from the resume line through the end, with tool changes handled as usual.
- Resume targets: a line number, an operation (from the headers or sidecar), or "where it stopped" (from the journal).

### 11.7 Job setup checklist (bridge panel)

Load the file. Analysis (§5.1 `analyze_file`) shows tools, operations, extents, clearance, runtime and envelope warnings. Then step by step, each ticked in the panel and logged:
1. Home.
2. Install the first tool.
3. BitZero: an XYZ corner, or Z only.
4. Measure the BitSetter reference.
5. Save and name the origin.
6. Check the stock against the extents.
7. Set the router dial.
8. Start.

A job that is resumed later goes through the same checklist, but restoring instead of probing.

### 11.8 Claude's part

MCP tools:
- `origins` (list, save, compare): Observe.
- `apply_origin`: Motion.
- `job_state`: Observe.
- `plan_resume(line|operation|"stopped")`: no authority needed. Returns the plan and a viewer snapshot.
- `start_resume`: Stream, plus the operator's confirmation.

Tool changes always need the operator.

# Session log and event format (contract v1)

One record format serves three purposes: the JSONL session log on disk, the records pushed on `WS /events`, and the records returned by `GET /log`. Schema: `bridge/schema/log-record.schema.json`. Each `data` payload has its own definition in that file.

## Files

```
~/Library/Application Support/ugs-bridge/
  token              MCP bearer token, 0600, regenerated each UGS launch
  bridge.json        discovery: {"format":"ugs-bridge-discovery/1","port":8765,"pid":…,"started":…,"api":"/api/bridge/v1"}, 0600
  notify.json        notification config (see bridge/config/notify.example.json), 0600
  origins.json       origin store (schema/origins.schema.json)
  logs/YYYY-MM-DD/HHMMSS-<port-slug>/
      session.jsonl  one record per line, append-only, flushed per record, fsync at least once a second
      files/<sha256>.nc   each streamed file, copied once
      job-<sha256-12>.state.json   job journal (schema/job-state.schema.json), atomic replace
```

- `<port-slug>` is the connection port with `/` replaced by `_` (`_dev_cu.usbmodem1101`, `tcp_127.0.0.1_2323`).
- A new session folder is created on each **connect**. Records from before any connection (lock changes at startup) go to `logs/YYYY-MM-DD/HHMMSS-noconn/`.
- Tests and the simulator set `UGS_BRIDGE_HOME` to override the `~/Library/Application Support/ugs-bridge` root. Every component (Java and Python) must honor it.

## Record envelope

```json
{"seq": 1042, "t": "2026-11-15T17:31:02.118-05:00", "mono": 3721.552, "session": "20261115-173001-tcp_127.0.0.1_2323",
 "kind": "rx", "src": "ugs", "data": {"line": "ok", "cmd_id": "c-17-0"}}
```

| Field | Meaning |
|---|---|
| `seq` | Strictly increasing integer for the bridge process lifetime (never resets on reconnect). Clients resume with `since=<seq>`. |
| `t` | Wall clock, ISO 8601 with milliseconds and offset |
| `mono` | Monotonic seconds since bridge start (for durations; immune to clock changes) |
| `session` | Session folder name, or `null` before the first connection |
| `kind` | See the table below |
| `src` | Who caused it: `operator` (UGS UI), `claude` (MCP API), `ugs` (UGS itself: stream, polling, interceptors), `controller` (unsolicited controller output), `bridge` (the bridge's own actions) |
| `data` | Payload for that kind |

## Kinds

| kind | When | `data` (abbreviated; full shape in schema) |
|---|---|---|
| `session` | connect / disconnect / bridge start / bridge stop / queue overflow | `{event: bridge_start\|connect\|disconnect\|bridge_stop\|overflow, port, driver, firmware?, ugs_version, bridge_version, dropped?}` |
| `tx` | every line written to the controller, including real-time bytes | `{line, cmd_id?, realtime?: "0x85"…, file_line?}` |
| `rx` | every raw line from the controller **except** `<…>` status reports | `{line, cmd_id?}` |
| `status` | status report changed (ignoring `FS` jitter below 1%), plus a heartbeat every 1 s | the `ControllerStatus` object (schema `status`) |
| `message` | UGS console text (MessageListener) | `{type: INFO\|VERBOSE\|ERROR, text}` |
| `event` | UGS events other than status | `{type: alarm\|controller_state\|stream\|file_state\|probe\|setting\|interceptor, …}` |
| `command` | lifecycle of a command queued through the bridge or UGS | `{id, origin, line, state: queued\|awaiting_operator\|sent\|ok\|error\|skipped\|alarm\|dropped, error_code?, alarm_code?, response?: [lines], expect?}` |
| `file` | file loaded or stream started | `{action: loaded\|stream_start\|stream_end, path, sha256, lines, copied_to?}` |
| `authority` | lock/unlock, level change, expiry, request from Claude | `{locked, level, reason, by}`. For a request from Claude (`by: claude`), `level` is the current level and `requested` the level asked for |
| `client` | MCP/viewer client connect, disconnect, auth failure | `{client: mcp\|viewer, action, remote}` |
| `notify` | notification sent or failed | `{trigger, text, level?, sinks: {telegram?: ok\|failed\|disabled, slack?: …}}` |
| `origin` | work offset changed or applied | `{wcs: "G54", wco: [x,y,z], method, tool?, bitsetter_ref?, origin_id?}` |
| `tool` | tool change / BitSetter measurement | `{event: change_requested\|confirmed\|measured\|reference_set, tool?, trigger_mz?, ref?, wco_z?, measurement_skipped?, note?}`. D22: an `M6` for the tool already in the collet with a known length logs only `confirmed` with `measurement_skipped: true` and `note: "same tool, length known, measurement skipped"` |
| `job` | journal checkpoint highlights (not every line) and resume actions | `{event: started\|op_start\|paused\|stopped\|completed\|resume_planned\|resume_started, file_sha256, line?, op?}` |

Rules:
- `tx` and `rx` are recorded in order of the wire. `status` reports are filtered (above), but real-time `?` polls are **not** logged as `tx` (they are implied by `status`). A `?` that UGS sends as a whole line (`?\n`, its `GetStatusCommand` at connect) is a line on the wire that GRBL answers with a report and `ok`, so it is logged as `tx` and its `ok` as `rx`.
- `cmd_id` links `tx`, `rx` and `command` records. For bridge-originated commands it is `c-<request>-<index>`. For UGS-originated commands it is `u-<GcodeCommand.getId()>`.
- File streaming at 100+ lines/s is logged in full. The writer must not block the communicator thread: use a bounded queue (≥ 100 000 records) drained by a writer thread. If the queue ever overflows, write a `session` record `{event: "overflow", dropped: n}`. Never drop silently.

## Capture details (Java plugin, package A)

How the plugin fills the records. Fields marked "extra" are additive (allowed by `additionalProperties`).

- **Wire records.** `tx` comes from `ICommunicatorListener.commandSent`, `rx` and `status` from `rawResponseListener`, all on UGS's communicator event thread, so their order is the wire order per direction. `file_line` is 1-based (`GcodeCommand.getCommandNumber() + 1`). `rx` lines are attributed to the oldest command still waiting for `ok`/`error`/`ALARM` (GRBL answers lines first in, first out); lines with nothing waiting have `src: controller` and no `cmd_id`. A `Grbl …` welcome (reset) clears the waiting list. This is exact because of two upstream patches: UGS dispatches `commandSent` before it writes the line (P6), so a command's event always precedes its answer, and events UGS drops from its queue when it resets the communicator (connect, soft reset, cancel) are still delivered to the capture (P4). `CONNECTING` is announced before the port opens (P4), so the welcome lines are captured.
- **Real-time bytes** (`!`, `~`, `0x18`, `0x85`, overrides; upstream patch P3) are `tx` records with `realtime: "0x.."`; `line` is the printable character or the hex value. `src` is `operator`, or `ugs` while an interceptor runs; the API marks its own bytes with `Capture.attributeRealtime(byte, src, cmd_id)` just before sending (claims the next matching byte within 2 s). Real-time `?` polls are only counted (`Capture.statusPolls()`).
- **`status`** is parsed from the raw GRBL 1.1 `<…>` report (values in the controller's reporting units; extra `units: "mm"|"inch"` when known). WCO, `Ov` and `A` are carried over between reports as GRBL intends. Extra `heartbeat: true` marks a record logged only because 1 s passed; heartbeats are only written while reports keep arriving. Firmware without `<…>` reports falls back to UGS's `ControllerStatusEvent` (positions in mm, `raw: ""`).
- **`command`** records: `sent` and the final state (`ok`/`error`/`alarm`/`skipped`, with `response`, `error_code`, `alarm_code`) for every command that is not a file line; for file lines only failures and skips (successful lines are fully described by `tx`/`rx`). Whole-line `?` queries get none (UGS completes them on the report and calls them not ok).
- **`src`** defaults: file lines, interceptor commands, commands sent while the controller is initializing and UGS's own `Get…Command` queries are `ugs`; other commands are `operator`. Commands registered with `CommandOrigins` (the API) use their own `cmd_id` and `src`.
- **`event`** extras: `controller_state {state, previous}` (UGS state names, lower case); `alarm {code, line}` from the `ALARM:n` line plus `alarm {ugs_alarm}` from UGS's `AlarmEvent`; `stream {event: started|paused|resumed|canceled|complete|paused_on_error}`; `file_state {state, path}`; `probe {position, units: "mm", coordinates: "machine"}`; `setting {scope: firmware, key, value}` or `{scope: ugs}`; `interceptor {state, previous, interceptor?, trigger?, file_line?, step?, options?}`.
- **`file`** extras: `stream_end` has `result: complete|canceled`; `stream_start` may have `processed_path`.
- **`session`** extras: `disconnect` may have `reason: "connection_closed"`; `bridge_start` has `home`.
- **`message`**: trailing newlines are stripped. Dropped because they duplicate other records (D16): UGS's console echoes of sent commands (`>>> G0 X1`, `>>> 0x85`, `>>>` for an empty line, optionally origin-tagged like `[claude] >>> …`), the echoed `ok`, and echoes of `<…>` reports of any type. Other console text stays, including multi-line responses such as `[PRB:…]\nok` and `$$` with setting descriptions.
- **`authority`**: `level` is the level in force (the operator's base level, Query by default, while locked or after unlocking; never an armed level). Extras: `motion_expires_at` while Motion/Stream is armed; `requested` on Claude's requests; `action: file_run`, `run_id`, `approved`, `path`, `sha256` for `POST /file/run` requests and the operator's answer. Development unlocks are logged `by: bridge` with a reason starting `DEVELOPMENT ONLY`.
- **`command`** records written by the API (Claude's requests): `queued`, `awaiting_operator`, `dropped` (with `reason`) and, for `?`, `ok` with the status line; extras `class` and `reason`. `sent` and the final state of other lines come from the capture.
- **`client`** extras: `endpoint`. `auth_failed` / `locked_refused` are logged at most once per 5 s per client type.
- **`origin`** from `POST /origins/apply`: `method: restored`, `origin_id`, `command_id`, `read_back` (from `$#`), `verified` (within 0.01 mm).

## Notify details (package G)

How the notifier (`bridge.notify`) fills `notify` records. Extras are additive.

- One record per attempt, written when the outcome is known (so a suppressed duplicate can appear before the send it duplicates). `src` is `claude` for `claude_*` triggers, `operator` for the panel's "Send test", else `bridge`.
- `trigger`: `stream_finished`, `stream_error`, `alarm`, `feed_hold`, `tool_change_waiting`, `connection_lost`, `claude_authority_request`, `claude_message`, `progress`, `test`, or `config`. `level`: `info|warn|alert`.
- `sinks` has both `telegram` and `slack`: `ok`, `failed`, `disabled` (sink off, placeholder or invalid), or `rate_limited`. Extra `suppressed: duplicate|rate_limit` says which; extra `errors: {sink: "HTTP 401: Unauthorized"}` for failures (secrets scrubbed).
- `trigger: config` is logged once at start and whenever notify.json's state changes (not per attempt): `sinks` lists only the sinks that can't send (`disabled`), extra `config: {telegram: off|placeholder|invalid|ready, slack: …}` and `file`. It is never sent.
- Tokens and webhook URLs are never written to the log.
- notify.json may carry an optional `dedup_seconds` (default 120, 0 = off): identical texts within that window are suppressed. `alert` level (ALARM, stream error, connection lost, failed tool change) ignores `rate_limit_per_minute` but is still de-duplicated.

Validate a log with `cd bridge && uv run python scripts/validate_log.py <session.jsonl>`.

## ETA and Telegram records (PLAN D25, D26)

- `event {type: "eta", reason: start|tick|op|tool|tool_change|done, …}` carries the `eta` object of `GET /job/state` (bridge/docs/api.md). Written by the API every 30 s while a stream runs and at operation, tool and tool-change boundaries.
- `client {client: "telegram", action: command|pause|ignored_sender|skipped_backlog|poll_error|reply_failed, remote?, detail?}`: the Telegram command poller. `remote` is the numeric chat id of an ignored sender (logged once per sender); `detail` is the command word, the pause result or a scrubbed error. The bot token never appears.
- notify.json additions: `triggers.progress_every_percent` now defaults to 25 (of the estimated machine time when the estimate is known, else of the file lines), `triggers.progress_every_minutes` (default 0 = off), `telegram.commands_enabled` and `telegram.allow_pause` (default true). Progress messages (`trigger: progress`) include a start message with the estimate and finish time; tool-change and job-finished messages gain the time left and the actual vs estimated machine time.

## Twin readiness records (PLAN D27)

- `event {type: "readiness", state: managed|warnings, warnings, infos, items: [{id, severity, message, fix}], first}` (src `bridge`): the findings of `GET /twin/readiness` (bridge/docs/api.md), written when they change (checked every 2 s after a status, origin, file, session, rx, event or tool record). `first` marks the first one after the API started.
- `event {type: "twin", action: "stock_confirmed", sha256, file, wco, by: gui|viewer}` (src `operator`): the operator confirmed that the loaded file's stock sits where the twin shows it, at that G54. Also kept in `<bridge home>/twin.json`.
- `event {type: "twin", action: "start_anyway"|"start_cancelled", warnings: [ids]}` (src `operator`): the answer to the warnings shown when the operator pressed Send in UGS.

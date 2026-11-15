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
| `session` | connect / disconnect / bridge start / bridge stop | `{event: start\|connect\|disconnect\|stop, port, driver, firmware?, ugs_version, bridge_version}` |
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
| `tool` | tool change / BitSetter measurement | `{event: change_requested\|confirmed\|measured\|reference_set, tool?, trigger_mz?, ref?, wco_z?}` |
| `job` | journal checkpoint highlights (not every line) and resume actions | `{event: started\|op_start\|paused\|stopped\|completed\|resume_planned\|resume_started, file_sha256, line?, op?}` |

Rules:
- `tx` and `rx` are recorded in order of the wire. `status` reports are filtered (above), but `?` polls are **not** logged as `tx` (they are implied by `status`).
- `cmd_id` links `tx`, `rx` and `command` records. For bridge-originated commands it is `c-<request>-<index>`. For UGS-originated commands it is `u-<GcodeCommand.getId()>`.
- File streaming at 100+ lines/s is logged in full. The writer must not block the communicator thread: use a bounded queue (≥ 100 000 records) drained by a writer thread. If the queue ever overflows, write a `session` record `{event: "overflow", dropped: n}`. Never drop silently.

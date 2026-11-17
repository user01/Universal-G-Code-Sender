# Bridge local API (contract v1)

Served by the UGS plugin. Consumed by `bridge/mcp` (Claude) and `bridge/viewer` (the operator's 3D view). Schemas live in `bridge/schema/`. Record shapes are in `log-format.md`.

## Transport and discovery

- HTTP/1.1 and WebSocket on **127.0.0.1 only**. Default port 8765; if it's taken, the next free port up to 8775. The chosen port is in `bridge.json` (see log-format.md).
- Base path `/api/bridge/v1`. The viewer is served at `/view/` (static files from `bridge/viewer`, packaged into the module jar).
- JSON in and out, UTF-8. Actions are `POST`; `GET` never changes anything.

## Clients, tokens and the lock

| Client | Token | Affected by MCP lock |
|---|---|---|
| `mcp` | `Authorization: Bearer <contents of token file>` | **yes** |
| `viewer` | per-launch viewer token, passed when UGS opens the browser: `/view/#vt=<token>`, then sent as `Authorization: Bearer v.<token>` | no. Read-only: GET endpoints and `/events` only |

- Missing or wrong token: `401 {"error":"unauthorized"}`, logged as a `client` record.
- **Locked** (SPEC §4.3, changed 2026-11-16 by the operator): `mcp` **reads stay open** (every GET and `WS /events`), so Claude can always watch. Every `mcp` action (POST: commands, realtime including hold/reset, notify, authority requests, file load/run, resume) gets `423 {"error":"locked_by_operator","message":"UGS access is locked by the operator"}` and nothing is sent. Locking drops Claude's queued commands; open streams stay open.
- The lock state and level change only from the UGS UI. No endpoint can unlock or raise authority. `POST /authority/request` only *asks*.

## Errors

`{"error": "<code>", "message": "<human text>", "required"?: "<level>"}`

| HTTP | code | when |
|---|---|---|
| 400 | `invalid_request` | bad JSON or fields |
| 401 | `unauthorized` | token |
| 403 | `authority_required` | level too low; `required` names the level |
| 404 | `not_found` | |
| 409 | `not_connected`, `busy`, `alarm`, `not_homed`, `interceptor_active` | state conflicts |
| 423 | `locked_by_operator` | MCP lock |
| 500 | `internal` | |

## Authority classification (enforced in Java; Python applies extra safety checks on top)

| Class | Lines | Minimum level |
|---|---|---|
| realtime | `POST /realtime` ops: hold, resume, jog_cancel, reset, status | none (always, if unlocked) |
| query | `?`, `$$`, `$#`, `$G`, `$I`, `$N`, `$$`-style reads, `$C` is **not** query | Query |
| confirm | `$H`, `$X` | Motion **and** operator dialog → command state `awaiting_operator` |
| motion | everything else (G/M words, `$J=`, `G10`, setting writes `$n=v`) | Motion |
| stream | `POST /file/run`, `POST /job/resume` | Stream **and** operator dialog |

Setting writes (`$n=value`, `$RST`, `$N0=…`) are classed as motion. Python refuses them by default.

## Endpoints

### Read (Observe)
| | |
|---|---|
| `GET /status` | `schema/status.schema.json#/$defs/BridgeStatus`: connection, controller status (incl. pins, WCO, Ln, buffers), homed-this-connection, authority, stream, interceptor prompt, current `seq` |
| `GET /log?since=<seq>&kinds=tx,rx,...&limit=<n≤5000>` | `{records:[LogRecord], next: <seq>}` |
| `GET /console?since=<seq>&limit=` | same, kinds fixed to `message,tx,rx,command` |
| `GET /settings` | `{raw: "…$$ text…", read_at, settings: {"$130": {value, name, units}}}`, from the last `$$` seen. Doesn't send `$$` itself |
| `GET /file` | `{path, sha256, lines, loaded_at}` or 404 |
| `GET /profile` | the active machine profile (`schema/profile.schema.json`) |
| `GET /origins` | `schema/origins.schema.json` |
| `GET /job/state` | the current or last journal (`schema/job-state.schema.json`) or 404 |
| `GET /command/{id}` | `CommandResult` |
| `WS /events?since=<seq>&kinds=…` | backlog after `since`, then live `LogRecord`s, one JSON per text frame. The server pings every 15 s |

### Act
| | Level | Body → Response |
|---|---|---|
| `POST /command` | per line (above) | `{lines:["G91 G0 Z5","G90"], tag?: "probe-z", expect?: "retract 5 mm", timeout_s?: 60}` → `202 {id, lines:[{index, line, class, state}]}`. Lines go **in order through UGS's own queue** (`sendGcodeCommand(GcodeCommand)`), tagged `origin=claude`. If any line needs a higher level than granted, the whole request is rejected (403) and nothing is sent. Results arrive as `command` records and at `GET /command/{id}`. |
| `POST /realtime` | always | `{op: hold\|resume\|jog_cancel\|reset\|status}` → `200 {sent: "0x21"}` |
| `POST /file/load` | Motion | `{path}` → `200 FileInfo` (path must be a readable regular file) |
| `POST /file/run` | Stream | `{}` → `202 {state:"awaiting_operator"}`. The operator's dialog shows name and SHA-256 |
| `POST /notify` | Observe | `{text, level: info\|warn\|alert}` → `202`. Rate-limited per notify.json |
| `POST /authority/request` | Observe | `{level: motion\|stream, reason}` → `202`. Shown in the panel and notified. Never grants anything itself |
| `POST /origins/apply` | Motion | `{origin_id}` → `202 {command_id}`. G10 L2 P1, then `$#` verify |
| `POST /job/resume/plan` | Observe | `{target: {line: n} \| {operation: "name"} \| {stopped: true}}` → `200 ResumePlan` (schema job-state `$defs/ResumePlan`). Computing a plan sends nothing |
| `POST /job/resume` | Stream | `{plan_id}` → `202 {state:"awaiting_operator"}` |

### CommandResult
```json
{"id":"c-17","tag":"probe-z","expect":"…","state":"done|running|awaiting_operator|failed|dropped",
 "lines":[{"index":0,"line":"G38.2 Z-10 F100","class":"motion","state":"ok","response":["[PRB:-3.000,-4.000,-41.250:1]","ok"],"error_code":null,"alarm_code":null}]}
```
`dropped` happens when the operator locks, an alarm or reset occurs, or authority expires before a line is sent.

## Versioning
Breaking changes bump `/v1`. Additive fields are allowed at any time; clients must ignore unknown fields.

## Additions after W1 (2026-11-15)

- `GET /file/content`: the loaded program as `text/plain` (Observe; viewer allowed). Needed by the live viewer, which can't read local paths.
- **Viewer WebSocket auth:** browsers can't set `Authorization` on a WebSocket, so the viewer passes its token as `WS /events?vt=<token>` (or the `Sec-WebSocket-Protocol: ugs-bridge.v1, vt.<token>` subprotocol). Only `v.`-class viewer tokens are accepted this way, never the MCP token. The server is loopback-only.
- **Ids:** `CommandResult.id` identifies the request (`c-17`). In `command` log records, `id` and `cmd_id` identify one line (`c-17-0`).
- **`src` on `rx`:** a reply to a command carries the `src` of whoever sent that command (`operator`, `claude`, `ugs`, `bridge`). Unsolicited controller output is `controller`.
- **Implementation notes (W2 E/F, 2026-11-15):**
  - Requests must carry `Host: 127.0.0.1:<port>` or `localhost:<port>` (400 otherwise); a WebSocket `Origin`, if sent, must be the same origin (403).
  - The viewer token is rejected on every `POST` with `403 authority_required` (no `required` field).
  - `POST /command` also refuses with **400** lines containing `!`, `~` or `?` (GRBL would treat them as real-time bytes, even in comments), line breaks, control or non-ASCII characters, lines over 256 characters, more than 200 lines; with **409** `busy` while a file streams and `interceptor_active` while UGS waits for a tool change (query-only requests pass). Level is checked before the connection (403 before 409).
  - Lines run one at a time; a line that ends `error`/`alarm`/`skipped` or times out (`timeout_s`, default 60) fails the request and drops the rest. `CommandResult` may carry `reason`.
  - `?` as a line is answered with a fresh status report (`response: ["<Idle|...>"]`) without going through UGS's queue.
  - `POST /file/run` → `202 {state: "awaiting_operator", id: "f-N", sha256}`; outcome in `authority` records (`action: file_run`). Only one run request at a time (409 `busy`).
  - `POST /job/resume/plan` accepts optional `tool` and `mid_contour` beside `target`. `POST /job/resume` answers `501 not_implemented` until the job runtime (J3/J4) lands.
  - `GET /log` / `/console` add `truncated: true, oldest_seq` when `since` is older than the in-memory ring; `WS /events` closes with 1013 if a client falls 100 000 records behind.
  - `GET /file/content` adds headers `X-Bridge-File` and `X-Bridge-SHA256`.
- **Operation addressing:** names can repeat within a file, so `{operation: …}` also accepts the 1-based operation number (`5` or `"OP 05"`).

## Additions after the first machine session (2026-11-16)

- **`controller_report` in `GET /status`:** `{seq, at, age_s, silent}` for the last controller status report, or `null` when not connected or before the first report. UGS polls about 5 times a second and the log writes a heartbeat every second, so a healthy controller keeps `age_s` near or below 1. On the machine the controller once froze (USB/EMI) while UGS still showed it connected, and `controller` kept saying Idle for minutes; `silent` is true from 5 s. `controller` is always the *last report*, never a guess of the present state.
- **`controller_silent` / `controller_responding` events:** the bridge checks every 2 s; after 10 s without a report while connected it logs `event {type: "controller_silent", age_s}` once (notified under the `connection_lost` trigger), and `controller_responding` when reports resume.
- **Clients waiting for motion:** a line's `ok` means "queued in GRBL's planner", not "done", and `GET /status` right after it can still show the state from before the motion. The MCP tools end every motion call with `G4 P0` (GRBL answers it only when the planner is empty) and then wait for a status report younger than that `ok` (`now − age_s` later than the moment the result arrived). Jogs get no `G4 P0` (GRBL answers G-code during a jog with error:9); their completion is the first fresh Idle report.
- **Motion is refused while the controller is silent** (`controller_report.age_s` ≥ 5): the position can't be trusted.

## Time estimates, live ETA and Telegram commands (2026-11-16, PLAN D24–D26)

- **`GET /file/estimate`** (Observe, works while Claude is locked; viewer allowed): the GRBL planner time estimate of the loaded file. `?lines=0` leaves out `line_end_s`. 404 without a loaded file, 503 `estimate_unavailable` if it couldn't be computed within 30 s.

  ```
  {version: 1, source: "grbl-planner", settings_from: "controller"|"profile", file, sha256, lines,
   total_s, machine_s, bitsetter_s, operator_s: null, naive_s,
   settings: {max_rate_mm_min[3], accel_mm_s2[3], junction_deviation_mm, arc_tolerance_mm, planner_blocks},
   line_end_s: [cumulative machine seconds at the end of each file line; index 0 = line 1],
   ops: [{index, name, tool, first_line, last_line, start_s, end_s}],
   tools: [{tool, number, name, s}],               // machine seconds per tool, in order of use
   tool_changes: [{line, from, to, at_s, bitsetter_s, operator_s: null}],
   assumptions: [text]}
  ```

  `machine_s` is motion and dwells only. `bitsetter_s` is the automatic part of every tool change (park, spin-down wait, BitSetter fast/slow probe, return) estimated with the same planner; the operator's time is unknown and never included. `total_s = machine_s + bitsetter_s`. `naive_s` (distance / feed, no acceleration) is there for comparison. The simulation: per-axis max rate and acceleration (`$110-$112`, `$120-$122`), junction deviation (`$11`) cornering, GRBL's reverse/forward planner passes with the controller's planner depth (14 blocks on the Shapeoko; the sender is assumed to keep the buffer full), arcs split per `$12`, rapids at the per-axis max rate (not `rapid_mm_min`, which is only Claude's feed cap), and a full stop at `G4`, spindle/coolant changes, `M6`, probes and program stops. Settings come from the controller's `$$` once the bridge has seen it, else from the profile's `motion` block. Results are cached per file SHA-256, settings and work offset (to 1 mm; only `G53` moves depend on it).

- **`GET /job/state`** gains `eta` (null when no stream has run since UGS started). The journal fields are unchanged; when no journal exists but a stream has run, the answer is `{eta}` only.

  ```
  eta: {active, result: null|"completed"|"canceled"|"disconnected", file,
        elapsed_s, paused_s, machine_elapsed_s, line_acked, line_done,
        estimate_total_s, estimate_machine_s, done_estimate_s, fraction, ratio,
        machine_remaining_s, adjusted_remaining_s, predicted_finish (ISO local),
        current_op, current_tool, op_remaining_s,
        next_tool_change: {line, tool, in_s} | null, tool_changes_remaining, waiting_for_tool_change}
  ```

  Progress is the highest acknowledged file line minus what is still in the controller's planner (up to its block count of timed lines; none once it reports Idle), turned into machine seconds with `line_end_s`. `paused_s` covers tool changes (interceptor active), feed holds (`Hold`/`Door`) and paused streams; `machine_elapsed_s = elapsed_s − paused_s`. `ratio` is actual / estimated machine time over the last 5 minutes (the whole run until 20 s of estimate are covered), clamped to 0.5–3. `adjusted_remaining_s = machine_remaining_s × ratio + bitsetter_s of the tool changes still ahead`; a change in progress is not "ahead". The operator's tool-change time is not in `predicted_finish`.

- **`event {type: "eta", reason, …eta fields}`** records: `start` (when the estimate is first known for a stream), `tick` (every 30 s while running), `op`, `tool`, `tool_change` (wait started or ended), `done`.
- **Telegram commands** are not an API endpoint: the notifier long-polls the Bot API (outbound only) and answers the configured private chat. They are logged as `client` records with `client: "telegram"` and `action: command|pause|ignored_sender|skipped_backlog|poll_error|reply_failed` (never the token). `pause` sends a feed hold as the operator (not subject to Claude's lock).

## Twin readiness (2026-11-16, PLAN D27)

Says when what the twin (3D view) shows is assumed, missing, or has drifted from the machine without the bridge knowing. Warnings never block anything.

- **`GET /twin/readiness`** (Observe, works while Claude is locked; viewer allowed):

  ```
  {state: "managed"|"warnings", warnings, infos,
   items: [{id, severity: "warn"|"info", message, fix}]}
  ```

  | id | severity | when |
  |---|---|---|
  | `machine_config_assumed` | warn | profile values not measured yet (`verified: false`, `*_verified: false`, `*source` starting "assumed", null `spoilboard_z_machine`, null BitZero bore); the message names them and the fix the ledger rows |
  | `machine_config_missing` | warn | the profile can't be read |
  | `controller_settings_differ` | warn | the last `$$` differs from `expected_settings` or the `motion` block |
  | `controller_travel_differs` | info | only `$130-$132` differ and soft limits are off (`$20=0`) |
  | `controller_settings_unread` | info | connected, no `$$` read this session |
  | `not_connected` | info | no controller |
  | `not_homed` | warn | connected, not homed since connecting or an alarm |
  | `g54_changed_outside` | warn | the newest origin is `observed` (G54 changed by another program, or differs from the store on connect) and the operator hasn't confirmed since |
  | `bitsetter_reference_stale` | warn | G54 Z changed after the reference was taken, not by a BitSetter tool change or by restoring an origin with the same reference |
  | `bitsetter_reference_missing` | warn | the loaded file changes tools, BitSetter tool changes are on, no reference |
  | `no_stock` | warn | the loaded file has no `(STOCK: …)` header and no sidecar `stock` |
  | `stock_not_confirmed` | warn | the operator hasn't confirmed this file's stock at the current G54 (X/Y moved since, or Z changed other than by a BitSetter tool change) |
  | `placement_unchecked` | info | a stock is known but G54 isn't (not connected) |
  | `stock_on_bitsetter`, `stock_off_spoilboard`, `cut_outside_stock`, `outside_envelope` | warn | the placement checks, at the live G54 (same rules as the viewer's `twin/placement.js`; shared cases in `bridge/testdata-public/twin-placement-cases.json`) |

- **`GET /status`** gains `readiness: {state, warnings, infos, ids: [warning ids]}`.
- **`POST /twin/confirm`** (body `{}`), **operator only**: accepted with the viewer token (the 3D view's Confirm stock button; the bridge panel calls the same code in-process). Claude's token gets 403 `operator_only` (423 while locked). Records `{sha256, file, wco, at, by}` in `<bridge home>/twin.json` and an `event {type: "twin", action: "stock_confirmed"}` record (src `operator`). 409 `not_ready` without a loaded file or a known G54. Answers `{confirmed, sha256, wco, at, readiness}`.
- **`POST /file/run`**: the 202 answer carries `readiness`, and the operator's dialog lists the warnings.
- **`event {type: "readiness", state, warnings, infos, items, first}`** records whenever the findings change (checked every 2 s after relevant records). **`event {type: "twin", action: "start_anyway"|"start_cancelled", warnings}`**: the operator's answer to the warnings shown when they press Send in UGS.

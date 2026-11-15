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
- **Locked** (SPEC §4.3): every `mcp` request gets `423 {"error":"locked_by_operator","message":"UGS access is locked by the operator"}`, and open `mcp` WebSockets are closed with code 4423. Nothing else leaks (no status).
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

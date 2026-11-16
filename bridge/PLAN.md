# ugs-bridge: implementation plan

Status 2026-11-15. Companion to `SPEC.md` (v0.1). Decisions here override the SPEC where they differ; fold them back into the SPEC at v0.2.

## Decisions (2026-11-15)

| # | Decision | Replaces |
|---|---|---|
| D1 | **Fork, not a standalone `.nbm`.** Everything (Java, Python, viewer) lives in this repo. | SPEC §3.1 / R1 choice |
| D2 | Base: tag **v2.1.30**, branch `bridge`, remote `upstream`. Rebase on each release tag. | — |
| D3 | **Java 25** (upstream moved from 17 after 2.1.23). Homebrew `openjdk@25`, via `bridge/scripts/dev.sh`. | — |
| D4 | Test without hardware over UGS's **TCP connection driver** → `bridge/sim` (resurfacer `SimulatedGrbl` behind a socket). No pty. | SPEC §8 pty question |
| D5 | **Notifications** to Telegram and/or Slack, sent by the plugin (so they work with no Claude session running). | new: SPEC §4.6 |
| D6 | The installed app is ad-hoc signed, so signing isn't a blocker for a fork build. | SPEC §10 Q1 |

## Features (what the fork adds to UGS)

| # | Feature | SPEC |
|---|---|---|
| F1 | **Complete session log**: every tx/rx byte, status, event and file, as JSONL with operator/Claude attribution | §4.1–4.2 |
| F2 | **MCP lock/unlock** toolbar toggle (starts locked; locked = Claude gets nothing) + **authority panel**: Observe / Query / Motion / Stream, armed by the operator; stopping always allowed; `$H`/`$X` confirmed | §4.3 |
| F3 | **Local API** (127.0.0.1, token) + **MCP server** so Claude can see and act; `[claude]` label in the console | §4.4, §5 |
| F4 | **Safety checks** on everything Claude sends: envelope from the profile, must be homed, bounded probes, modal hygiene | §5.2 |
| F5 | **3D viewer**: toolpath by operation, live tool, probes, material removal, warnings, snapshots for Claude | §6 |
| F6 | **Notifications** to Telegram/Slack: done, error, alarm, hold, tool change waiting, Claude requests | PLAN D5 |
| F7 | **Origins store and recover**: every G54 change recorded; named origins pinned to jobs; restore after homing | §11.3 |
| F8 | **BitZero + BitSetter tool-length workflow**: reference after zeroing, automatic re-zero after each `M6` | §11.4–11.5 |
| F9 | **Clearance heights and park positions** from the profile and job, used by every generated move | §11.2 |
| F10 | **Job journal and resume**: survives stop, crash or power cut; resume at a line, operation or "where it stopped" with a safe plan | §11.6 |
| F11 | **Job setup checklist**: home → tool → BitZero → BitSetter ref → save origin → start | §11.7 |
| F12 | **Machine profile** for the Shapeoko 4, with mismatch warnings against `$$` | §7 |

## Findings in the v2.1.30 source

- `BackendAPI.sendGcodeCommand(GcodeCommand)` lets the bridge build each command and track it by `getId()`, so it knows which commands are Claude's without a core change.
- The console echo `>>> cmd` comes from `firmware/grbl/GrblCommandLogger` (and its grblHAL twin). Showing `[claude] >>> …` needs an origin on `GcodeCommand`: **upstream patch P1**.
- New since 2.1.23: the **command interceptor** framework (`services/interceptor/CommandInterceptor`, `InterceptorStateEvent`) with a tool-change dialog (`ugs-platform-plugin-interceptor`, #3149). Effects:
  - `M6` handling: the SPEC's "refuse M6" may become "allow it; UGS pauses for the tool change". Notify on `InterceptorStateEvent`.
  - It's a candidate place for the stream-time authority gate.
- Error toasts (#3228) exist only in `ugs-fx` (the new JavaFX UI), not in the Platform UI the operator uses.
- The pendant (`ugs-pendant`) uses Jersey + Jetty 12 (ee10), already bundled. The bridge API can reuse the same stack, bound to 127.0.0.1.

## Upstream patches (keep this list short)

| ID | File(s) | Change | Why |
|---|---|---|---|
| P1 | `types/GcodeCommand`, `GrblCommandLogger`, `GrblHalCommandLogger` (+ `GrblCommandLoggerTest`) | optional `origin` field (`get/setOrigin`); `commandSent` echo becomes `[origin] >>> cmd` when set. Landed in W2 E/F | `[claude]` in the console |
| P8 | `ugs-platform/application/pom.xml` (surefire) | `ugs.bridge.home` → `target/bridge-home` for `ApplicationTest` | The platform test booted the bridge and wrote to the real bridge home |
| P9 | `ugs-platform/ugs-platform-visualizer`: new `jogl/GlStartup.java` (`ModuleInstall`), `OpenIDE-Module-Install` in `src/main/nbm/manifest.mf`, `org-openide-modules` dependency in `pom.xml` | Calls `GLProfile.initSingleton()` in `restored()`, off the EDT and before any window opens | Startup deadlock on macOS (2026-11-16): the visualizer's first `GLCapabilities` on the EDT waits for the AppKit thread (`OSXUtil.RunOnMainThread`) while AppKit waits for the EDT in an accessibility query (`CAccessibility.invokeAndWait`). The race can't be reproduced on demand; verified by build only |
| P2 | `ugs-platform/pom.xml`, `ugs-platform/application/pom.xml` | register `ugs-platform-plugin-bridge` | module in the app build |
| P3 | `communicator/ICommunicatorListener` | new default method `realtimeByteSent(byte)` | real-time bytes visible to listeners |
| P3 | `communicator/BufferedCommunicator` | `sendByteImmediately` dispatches `realtimeByteSent` before the write | hold, resume, reset, jog cancel, overrides logged as `tx` |
| P3 | `communicator/event/CommunicatorEventDispatcher` | forwards `realtimeByteSent` | |
| P3 | `communicator/event/AsyncCommunicatorEventDispatcher`, `AsyncCommunicatorEvent`, `AsyncCommunicatorEventType` | queue `REALTIME_BYTE_SENT` in order with the other events | wire order kept |
| P4 | `communicator/event/CommunicatorEventDispatcher` | listener set `HashSet` → `CopyOnWriteArraySet` | `ConcurrentModificationException` when a listener attaches while events are dispatched |
| P4 | `communicator/event/AsyncCommunicatorEventDispatcher`, `AsyncCommunicatorEvent`, `ICommunicatorListener` | `reset()` no longer clears the queue: queued events get an older generation and go only to listeners whose new default `receivesDiscardedEvents()` is true | the initializer's `resetBuffers()` at connect/soft reset threw away the second welcome line (`[MSG:'$H'\|'$X' to unlock]`), seen on the simulator |
| P4 | `GrblController` | `openCommPort` sets `CONNECTING` before `comm.connect` (as `AbstractController` does) | the bridge attaches on that event, before the first welcome line |
| P5 | `services/MdnsService` | jmdns only if `-Dugs.mdns.enabled=true` (`MdnsService.isEnabled()`) | `TCPConnection` started mDNS on every IPv4 interface; the bridge must not advertise on the LAN. Cost: no `_telnet._tcp` device list for the TCP driver unless enabled |
| P6 | `communicator/BufferedCommunicator` | `streamCommands` dispatches `commandSent` before `sendStringToComm` | a fast answer could be dispatched before its command's `commandSent` (UGS's own controller has the same race); with it, `rx` attribution is exact without guessing |
| P7 | `services/interceptor/InterceptorPrompt` | optional `title` and `message` (2-arg constructor kept) | the BitSetter prompt says "Change to T21 Whiteside RU1600 1/8 in upcut" in UGS's dialog |
| P7 | `services/interceptor/CommandInterceptor`, `CommandInterceptorService` | `default boolean restoresPosition()`; the service skips its `G0 X Y` / `G0 Z` restore when false | the stock restore rapids to the work Z captured before `M6`; the posts retract with `G53 G0 Z-5`, which UGS's parser records as **work** Z-5, i.e. 5 mm below the stock top after the change. The BitSetter interceptor returns to the pre-change XY at safe Z itself (D10) |
| P7 | `services/interceptor/CommandInterceptorService` | state goes `INACTIVE` before the stream is un-gated and restarted | the communicator refills the RX buffer at once and `findInterceptor` only matches while inactive, so an `M6` within ~128 bytes of the previous one went to GRBL raw (`error:20`); seen on the simulator |
| P7 | `ugs-platform-plugin-interceptor/GenericDialog` | shows the prompt's title/message when present | |
| — | anything else | must be justified here | |

## Notifications (new SPEC §4.6, draft)

- Sinks: **Telegram** (bot token + chat id, Bot API `sendMessage`) and **Slack** (incoming-webhook URL). Either or both. Config is read from `~/Library/Application Support/ugs-bridge/notify.json` (0600) and edited in the bridge panel, with a "send test" button.
- Triggers (each can be toggled; rate-limited and de-duplicated):

  | Trigger | Default |
  |---|---|
  | stream finished (name, duration, line count) | on |
  | stream stopped on error | on |
  | ALARM (code and text) | on |
  | feed hold lasting longer than N seconds | on |
  | tool change waiting (interceptor) | on |
  | connection lost | on |
  | Claude asks for authority (e.g. to arm Motion) | on |
  | Claude's own messages (MCP tool `notify(text)` → `POST /notify`) | on |
  | progress every N % | off |

- Every notification is also written to the session log (`kind: "notify"`).
- Later, not v1: two-way replies (for example, approving from Telegram). Approvals stay in the UGS GUI for now.

## W0 status (2026-11-15): done
- Contracts: `bridge/docs/api.md`, `bridge/docs/log-format.md`, `bridge/schema/*.schema.json` (validated), profile `bridge/profiles/shapeoko4-standard.json` (placeholders marked `verified: false`).
- Module `ugs-platform-plugin-bridge` builds and is packaged into the app cluster. `BridgeLock` (starts locked) is tested, and the panel with the lock toggle and the `@OnStart` service are registered (checked in the generated layer). It has **not been launched in the GUI** because the serial port is in use: launching the dev app could auto-connect.
- Python uv workspace `bridge/` (members `mcp`, `sim`) with resurfacer as a read-only path dependency.
- Test corpus: `bridge/testdata/nc` (gitignored, personal data). See `bridge/TESTDATA.md`.
- Decision D7: J1 (resume/BitSetter logic) is Java, not Python, so it works without Claude.

## W1 status (2026-11-15): all five packages merged

| Pkg | Result | Tests |
|---|---|---|
| A capture + log | `bridge.capture`, `bridge.log`: every UGS source → JSONL per log-format.md, non-blocking writer, file archive, ring buffer for the API | Java module 104/104 (with J1) |
| B simulator | `bridge/sim`: `ugs-sim` on loopback TCP; BitSetter/BitZero pins, tool length, DTR-reset alarm, real-time bytes, time scale. **UGS's own GrblController drove it** (connect, home, jog, probe, tool change, full 26 676-line stream) | 32 + opt-in Java check |
| C MCP + file tools | `bridge/mcp`: Observe/Query tools, `analyze_file`, `prepare_file`, bridge client (lock/403/down mapping), fake bridge, `docs/post-headers.md` | 87 |
| D viewer V1 | `bridge/viewer`: offline Three.js 0.170 (vendored), ops colored, scrubber, section, warnings, PNG/glb; dr_ryan builds in ~55 ms | 30 (node) |
| J1 job core | `bridge.job`: job model, safe points, resume-point finder, `ResumePlanner`, BitSetter math, probe sequences, journal, origin store | (in the 104) |

Python total: 119 passed, 1 skipped (opt-in Java check). ruff + mypy strict clean. **Nothing has run in the UGS GUI or on the machine yet.**

### Decisions from the W1 reports
- D8 **Preamble order:** retract first (`G21 G53 G0 Z<safe>`), then modal restore. SPEC §11.6 is updated to match.
- D9 **BitSetter `ref` invalidation:** only when the homing configuration changes (a signature of `$22`–`$27`) or the BitSetter position in the profile changes, not on every homing cycle. A re-measure of the same tool on resume still guards against drift.
- D10 **Tool-change return:** after measuring, the interceptor returns to the **pre-change XY at safe Z** before handing back to the stream (J3), so the program's next `G0 Z` doesn't come down at the park position.
- D11 **Tool-change block ownership:** the `M6` block belongs to **no operation**. An operation ends before an intervening `M6` (C and J1 already do this; the viewer must align, follow-up V-1).
- D12 **Sidecar name:** canonical `<stem>.job.json`; `<file>.nc.job.json` is also accepted.
- D13 **Skip `command` records for successful file lines;** tx/rx cover them.
- D14 **Worktrees:** the resurfacer path in `bridge/pyproject.toml` is absolute.

### Decisions from the operator's review answers (2026-11-15)
- D15 **Resume spin-up dwell:** after `M3`, use the file's own `G4 P…` if the program has one (the operator's post emits `G4 P2.0`), otherwise profile `spindle.spin_up_s` = 2.0 s.
- D16 **Drop UGS console echoes from the session log.** `>>>` and `ok` console messages duplicate tx/rx. Other console messages stay.
- D17 **Upstream core edits are approved** (P1, P3, P4 and any others needed). Keep each small and listed.
- D18 **The dev GUI may run** with an isolated home (`bridge/.dev-home`, so the installed UGS's settings are untouched) against the simulator over TCP. The machine isn't connected, so no live machine work yet.
- D19 **No machine capture or measurements for now.** Proceed on sourced or assumed values, all tracked in `bridge/docs/machine-ledger.md` (BitZero V2 13 mm sourced; bore unknown, so XY corner probing stays simulator-only).
- D20 **BitSetter tool changes are on by default** (stock UGS tool-change handling off while on); the operator can turn them off in the panel and the choice persists in `runtime.json` (a file from before D20, without `tool_changes_chosen`, is turned on).
- D21 **Claude may switch the router off (`M5`, `S0`) but never on:** `M3`/`M4` and non-zero `S` are refused; the operator starts the router.
- D22 **Skip the BitSetter measurement when the length is known:** an `M6` for the tool in the collet, measured in this connection with no reset/alarm/homing/reconnect/G54 Z change since, sends only the `T` word, logs `tool` `confirmed` with `measurement_skipped`, and doesn't notify. Any doubt runs the full change.
- D23 **The MCP lock gates actions, not reads** (operator, 2026-11-16). Locked, Claude can still GET everything and keep `WS /events` open, so it can watch and advise; every POST (including hold/reset and notify) gets 423. Supersedes the "reads included" rule in SPEC §4.3.

### Upstream patch candidates (from A), approved (D17); landed in W2 A2 (see the table above)
- P3 a listener hook in `BufferedCommunicator.sendByteImmediately`, so real-time bytes (hold, resume, reset, overrides) are logged as tx. Without it, operator real-time bytes are invisible in the log.
- P4 make `CommunicatorEventDispatcher.communicatorListeners` thread-safe, or attach before `comm.connect`. This avoids a rare `ConcurrentModificationException` and the lost welcome lines at connect.

### Review set (2026-11-15)
HTML review reports for W0 and W1 (private artifacts): index https://claude.ai/artifact/Sjq1TZk1oD2FkXacBjn33S

### Findings from the review runs
- **A-2 (bug, fixed in W2 A2):** `rx.cmd_id` drifts. UGS's `GetStatusCommand` sends `?` as whole lines at connect. Capture skips them in `commandSent`, so their `ok`s raise the early-answer counter, which is never cleared. On the simulator, 120 of 152 rx answers carried the wrong `cmd_id`; `command` records are unaffected. Fix: `?` lines are logged as `tx` and wait for their `ok`; the early-answer counter is gone because P6 makes `commandSent` always precede the answer. Simulator rerun (`UgsSimIntegrationTest`): every `rx` names the right command.
- J-2: round the stored BitSetter `ref` and WCO values to 0.001 mm (`-7.520000000000003` appears in the journal).
- V-1 is confirmed: the viewer's marker ops still contain the following M6 block.
- The simulator has no deceleration, so `Hold:1` lasts one report. Reset-after-hold tests are optimistic.
- dr_ryan streams through UGS core against the simulator in 200 s at time-scale 20 (311-line default: 57 s).

## W2 status (2026-11-15): all seven packages merged (33e18810)

| Pkg | Result | Report |
|---|---|---|
| A2 | cmd_id drift fixed (62/85 → 0), D16 echoes dropped, real-time bytes logged; core P3–P6; opt-in `UgsSimIntegrationTest` | https://claude.ai/artifact/3DHLBb7GzdUJ2LhS2394tw |
| E+F | JDK-only HTTP/WS server on 127.0.0.1, 21 endpoints (`/job/resume` 501), padlock toolbar + menu + Cmd/Ctrl+Shift+L, authority levels with expiry, `$H`/`$X`/file-run dialogs, P1 `[claude]` console label; verified in the dev GUI against the sim | https://claude.ai/artifact/58kTEWeogtzvMY2hZp8MW8 |
| G | Telegram/Slack notifier, triggers, rate limit, settings panel class (not yet placed) | https://claude.ai/artifact/VwPh84pMrFxyYmAS47VHT6 |
| H | 14 MCP motion/job tools behind the §5.2 checker (126 cases) | https://claude.ai/artifact/QrZ5BpYth5FfMQ7NzZbiPR |
| J | `runtime`: origins tracking, journal, BitSetter tool-change interceptor (D10 return), P7; sim IT shows WCO_z +12.500 for a +12.5 mm tool | https://claude.ai/artifact/1NHBxoXbMeMUUFfK9E2pwd |
| S | sim BitZero V2 XY/XYZ probing (placeholder bore), probe-away, feed-hold decel | https://claude.ai/artifact/VmWcGbPLPccpKAvxw9UVFG |
| V | viewer D11 fix + live mode (fake-bridge verified) | https://claude.ai/artifact/XddidMqMr6jq6JykYeCxA2 |

Totals after integration: ugs-core 951, bridge module 240 (+ both sim ITs), Python 274, viewer 51, all passing.

### W3 integration queue
- Wire `JobRuntime` into the API: `/origins`, `/origins/apply`, `/job/state` (live journal), `/job/resume` (replace 501, operator dialog, preamble + stream from line).
- Place `NotifySettingsPanel` and the "BitSetter tool changes" option in the Claude panel layout; the status-bar lock mirror.
- Capture `file_line` off by one (getCommandNumber is already 1-based).
- Decide: resume UGS's paused queue after a failed Claude line; synthesize `/status.alarm` for the DTR-reset alarm; `?vt=` token form (raw vs `v.`); `GET /file/sidecar`; dev CORS.
- Job setup checklist panel (J4) and resume dialog with the viewer showing the plan.
- Live viewer against the real plugin API (`Open 3D view`).
- Real machine: ledger A–D once connected.

### Machine session 1 (2026-11-16): fixes on `bridge-session1-fixes`
First session on the real Shapeoko (results in `docs/machine-ledger.md`: A1–A4, A6, B1, B6–B8, B10, C7, C8, D1, D2, D2a, D5). Bugs found there and fixed:
- **Stale motion results:** MCP move/jog/probe returned "Idle" at the pre-move position right after GRBL's `ok` (4 times live). Motion calls now end with `G4 P0` (not after jogs) and wait for a status report younger than the `ok`; regression test with a UGS-like status interval in the fake bridge.
- **Silent controller:** after a USB/EMI freeze the status kept saying Idle for 5 minutes. `GET /status` gains `controller_report {seq, at, age_s, silent}`; MCP status says NO STATUS FROM CONTROLLER and motion is refused past 5 s; the bridge logs `controller_silent` after 10 s (notified under `connection_lost`) and `controller_responding` when reports resume.
- **Tool-change prompt during spin-down:** the prompt came 1.2 s after `M5` with the bit still coasting. Profile `spindle.spin_down_s` (8 s, assumed, ledger C8); the routine dwells out the rest after parking and the prompt says to wait for the bit to stop.
- **BitRunner is on/off only (C7):** profile `spindle.s_off_verified` 10000 / `s_on_verified` 18000; `analyze_file` errors on S at or below the off value (or none), warns between (the operator's files use S16000: untested), and reports the router dial as the speed.
- Profile: BitZero 13.35 mm (paper test), BitSetter X −4.2 Y −448.2, 14 planner blocks. Tests read the thickness from the profile.
- Still open from the session: confirm 13.35 by a re-probe; S16000 on the BitRunner; time the spin-down; homing repeatability 5×; pocket test for D2; D3/D4.

### Follow-ups queued for W2 (kept for history)
- V-1 viewer: tool-change block outside operations (D11); live source with `GET /file/content` + `?vt=` WebSocket auth.
- S-1 sim: XY probing (`G38.x` with X/Y) for BitZero corner tests. ~~Promote the Java compatibility harness into a JUnit integration test~~: done in W2 A2 (`UgsSimIntegrationTest`, opt-in with `-Dugs.bridge.it=true` or `UGS_SIM_IT=1`).
- J-1 job: BitZero XYZ-corner sequence; the envelope Z check uses the post-measurement WCO.
- A-1 capture: confirm rx `src` follows the originating command (api.md additions). Real-time bytes: P3 landed (W2 A2); the API must call `Capture.attributeRealtime` before it sends one.
- Operator's post (`post/user.py`): in step20/29/30/32/50 the `(CUT EXTENT …)` header is offset −20 mm in X/Y from the G-code (blank vs. jig coordinates). `analyze_file` warns about it.

### W2 A2 (capture fixes + core patches): done
- A-2 fixed (P6 + `?` lines as `tx`), D16 echoes dropped, P3 real-time bytes as `tx`, P4 thread-safe listeners and no lost welcome lines, P5 mDNS opt-in.
- `UgsSimIntegrationTest` (opt-in, ~25 s): UGS's GrblController over TCP to `ugs-sim` with Capture + SessionLog, checked line by line against the simulator's byte log: all `tx`/`rx` present and in order, every `rx` names the right command, all real-time bytes except `?` polls logged, records schema-valid, `user.home` untouched, mDNS never queried.

### W2 E+F (local API, MCP lock, authority, Claude command path): done
- **Server stack: JDK-only** (`bridge.api.http.LiteHttpServer`, HTTP/1.1 keep-alive + RFC 6455 WebSocket, one virtual thread per connection, ~600 lines). Not the pendant's Jetty 12/Jersey: those jars are inside `ugs-platform-ugslib` but not in its public packages, so a NetBeans module can't link them without another upstream edit, and Jersey/HK2 rely on ServiceLoader, fragile under module class loaders. The API is a few JSON routes, static files and one WS endpoint for 1-2 local clients.
- 127.0.0.1 only, 8765 then up to 8775; `bridge.json` + `token` written 0600 (atomic, new token each launch); viewer token per launch, only in the `/view/#vt=` URL; `Host`/`Origin` must be loopback.
- Every endpoint of api.md. `POST /job/resume` validates the plan and authority, then answers **501** until the J3/J4 runtime exists. `GET /job/state` serves the newest `job-*.state.json` under `logs/`.
- Authority: lock + Observe/Query/Motion/Stream (`bridge.api.Authority`); Motion/Stream expire after 15 min without use and on any alarm, reset or disconnect (homed state survives probe alarms 4/5); every change is an `authority` record. Lock toolbar toggle (own toolbar "Claude", padlock drawn in code), *Machine → Lock Claude access*, Cmd/Ctrl+Shift+L; panel shows levels, expiry countdown, Claude's pending request, API port, *Open 3D view*, stock pendant warning.
- Claude's lines go one at a time through `sendGcodeCommand(GcodeCommand)` with `origin=claude` (P1) and `CommandOrigins` ids `c-N-i`; `$H`/`$X` wait for a non-modal operator dialog; lock/alarm/reset/disconnect/expiry drop unsent lines (`dropped`).
- Profile: the module jar bundles `bridge/profiles/shapeoko4-standard.json`; `<bridge home>/profile.json` overrides it (re-read per request). The viewer (`index.html`, `styles.css`, `src/`, `vendor/`) is packaged and served at `/view/`.
- Dev only: `-Dugs.bridge.dev.unlock=true` (+ optional `-Dugs.bridge.dev.level=`) honored only with a loopback `-Dugs.bridge.dev.connect`; logged as an `authority` record by `bridge` saying DEVELOPMENT ONLY. Snapshot requests may contain `panel` / `close-dialogs`; dialogs are composited into the PNG.
- Tests: classifier, authority/expiry, discovery perms, 27 HTTP/WS tests in-process, and `ContractPythonTest` (the MCP server's `BridgeClient` against the Java server: status, query, settings, 403, 401, events, lock → 4423/423).

## Work packages

The contract (W0) comes first and is done in the main session. After that, the four W1 packages share no files and can run as parallel subagents in separate worktrees.

### W0: contracts (main session, before any fan-out)
- `bridge/docs/api.md`: endpoints, auth, the JSON shape of `/status`, the event envelope on `WS /events`, command ids and results, and error codes.
- `bridge/docs/log-format.md`: the JSONL record schema (SPEC §4.2) with examples for each `kind`.
- JSON Schema files in `bridge/schema/`, used both by Java tests and by the Python fake bridge.
- Module skeleton: `ugs-platform-plugin-bridge` builds and loads in `dev.sh run` with an empty docked panel (P2). This proves the module wiring before parallel work starts.

### W1 (parallel)
| Pkg | Scope | Depends | Done when |
|---|---|---|---|
| **A: plugin capture + log** (B1) | listeners from SPEC §4.1; JSONL writer; file copy + SHA-256; `ugs-bridge tail` | W0 | connected to the simulator, a session log validates against the schema, and every tx/rx line is present |
| **B: simulator over TCP** | `bridge/sim`: resurfacer `SimulatedGrbl` on `tcp://127.0.0.1:2323`, matching the Shapeoko profile and the DTR-reset/alarm-on-connect behavior | — | UGS connects with the TCP driver, homes, jogs and streams a file |
| **C: MCP server skeleton + file tools** (part of M1) | `bridge/mcp` with uv, FastMCP; `analyze_file`, `prepare_file`; post-header parser; fake bridge that serves the W0 schema | W0 | pytest passes; tools work on the user's real `.nc` files |
| **D: viewer V1 offline** | `bridge/viewer`: Three.js; toolpath colored by operation from the post headers and sidecar; envelope; scrubber | — | opens a file with no connection and renders the user's jobs correctly |

### W2 (after W1)
| Pkg | Scope |
|---|---|
| **E** (B2) | read-only HTTP + WS API on 127.0.0.1 with a bearer token; **MCP lock toggle** (toolbar, starts locked); authority panel; pendant warning |
| **F** (B3 + P1) | `POST /command`, `/realtime`; `[claude]` console label; Motion authority and its expiry rules; `$H`/`$X` confirmation dialogs |
| **G** (D5) | notifier: Telegram and Slack sinks, triggers, panel config, `POST /notify` |
| **H** (M1 + M2) | MCP Observe, Query and Motion tools against E/F; safety checks (§5.2) |

### W2b: multi-tool jobs (SPEC §11)
| Pkg | Scope | Depends |
|---|---|---|
| **J1** | **Java** (pure package `com.willwinder.ugs.nbp.bridge.job`, no NetBeans/Swing deps), so resume works from the UGS panel with no Claude session: resume-point finder (planner-buffer aware), safe-point search, resume-plan generator (`ResumePlan` schema), BitSetter `ref` math, journal model and writer, post-header/sidecar job model. Uses UGS's own `GcodeParser`. MCP reaches it via `POST /job/resume/plan`. Tested against `bridge/testdata/nc`. | W0 |
| **J2** | Java: origins store (auto-record every WCO change), apply/verify, journal writer | A |
| **J3** | Java: BitSetter tool-change interceptor (registered via `CommandInterceptorService.register`, stock one disabled), profile-driven park and probe | J2, B (simulated BitSetter pin) |
| **J4** | Java panel: job setup checklist, resume dialog showing the plan; viewer shows the resume path | J1, J2, V1 |

The simulator (B) must model the BitSetter and BitZero pins and tool length so J3/J4 can be tested without the machine.

### W3
Viewer V2: live tool, probes, material removal, `view` snapshots. Then S1 stream authority, the hardware checklist, and `.app` packaging of the fork build.

## Open items needing the operator
1. Telegram or Slack credentials: fill in the placeholders in `~/Library/Application Support/ugs-bridge/notify.json` (0600). A copy of the template is at `bridge/config/notify.example.json`.
2. Real `.nc` files: about 150 in `~/Downloads`. Copy a representative set into `bridge/testdata/` for packages C and D.
3. Whether package B should mirror the measured machine values from the resurfacer capture (`tests/fixtures/machines/`), if that capture exists yet.
4. ~~Remote~~: `origin` = `git@github.com:user01/Universal-G-Code-Sender.git` (the user's fork), `upstream` = winder.
5. The serial port is in use by the operator for now, so no live UGS runs until they say so. W0 and W1 can be done and tested without it: build, unit tests, and the simulator over TCP once UGS can run.

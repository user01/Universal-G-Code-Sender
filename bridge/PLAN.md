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
| P1 | `types/GcodeCommand`, `GrblCommandLogger`, `GrblHalCommandLogger` | optional `origin` tag; logger prefixes it | `[claude]` in the console |
| P2 | `ugs-platform/pom.xml`, `ugs-platform/application/pom.xml` | register `ugs-platform-plugin-bridge` | module in the app build |
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
| **J1** | Python core, pure and fully unit-tested: resume-point finder (planner-buffer aware), safe-point search, resume-plan generator, BitSetter `ref` math, journal schema. Test against the 150+ real files in `~/Downloads`. | C |
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

# Machine ledger: what we still need from the real Shapeoko

Everything in the bridge has been proven only in unit tests and against `bridge/sim`. This ledger lists each fact about the real machine that the code currently **assumes**, why it matters, how to find out, and the value we use until then. Work through it on scrap wood with a hand on the E-stop. Update the status column and the profile (`bridge/profiles/shapeoko4-standard.json`, `verified: true`) as items are settled.

Status: `assumed` (best guess, in use), `sourced` (from documentation or community, not measured on this machine), `verified` (measured here), `blocked` (needs something first).

## A. Read-only capture (no motion; one session)

Run `uv run shapeoko-capture` from `../resurfacer` with Carbide Motion and UGS closed. That one run settles A1–A6.

| # | Fact | Why it matters | Current value | Status |
|---|---|---|---|---|
| A1 | `$130`/`$131`/`$132` max travel | Envelope checks; the simulator's `$$` | 445 / 456 / 95 in the profile; the controller read **845 / 850** on 2026-11-15 | assumed |
| A2 | `$110`–`$112` max rates | Rapid-rate estimate (`rapid_mm_min`) | 5000 mm/min | assumed |
| A3 | `$10` status mask, `$22`–`$27` homing settings | MPos vs WPos parsing; the homing signature that invalidates the BitSetter reference (D9) | MPos; pull-off 3 mm (community: machine reads −3,−3,−3 after homing) | assumed |
| A4 | Banner, `$I` (`[VER:…]`, `[OPT:…]`), `$N0`/`$N1` | Simulator realism; startup blocks Carbide Motion may have left (`M56P0`?) | `Grbl 1.1f`, `[OPT:V,15,128]` | assumed |
| A5 | Opening the port resets the controller (DTR) and comes up in alarm | Connect handling, "must re-home" rule | yes (seen 2026-11-15) | sourced |
| A6 | Pin letters in `Pn:` for the BitZero and the BitSetter, and whether `$5` inverts them | Probe-clear checks before every probe | both `P`, not inverted | assumed |

## B. Probing hardware (needs calipers, then gentle motion)

| # | Fact | Why it matters | Current value | Status |
|---|---|---|---|---|
| B1 | **BitZero V2 total thickness** (aluminum block + plastic base), measured with calipers | Z zero on the stock. A wrong value cuts too deep or air-cuts | **13.0 mm** (11 mm aluminum + 2 mm plastic base, community-measured) | sourced |
| B2 | BitZero V2 footprint | Simulator geometry; clearance moves around the block | 58 × 58 mm (from a published BitZero macro) | sourced |
| B3 | **BitZero V2 bore diameter** and the offset from the bore center to the corner the block registers against | XY corner probing: the bore center is found by probing inside it, then the corner is the center minus this offset | unknown. Not published by Carbide 3D | blocked |
| B4 | Z-only placement: block flat on the stock, or with the edge guides on the surface (forum: wrong choice gives 2–5 mm errors) | Which thickness applies in Z-only mode | flat on stock, 13 mm | assumed |
| B5 | Probe failure reply on this firmware: `ALARM:4`/`ALARM:5`, `[PRB:…:0]`, and whether an `ok` follows | Command completion and error handling | `[PRB…:0]` then `ALARM:5`, no `ok` | assumed |
| B6 | **BitSetter machine X/Y** (spindle centered over the button) | Every tool measurement | X −4, Y −449 | assumed (from the 2026-11-15 session) |
| B7 | BitSetter trigger machine Z with a reference tool, safe start Z, and how far is safe to probe | Bounded probe travel | start Z −5, max travel 90 mm | assumed |
| B8 | BitSetter **repeatability**: 10 measurements of one tool, report spread | `repeat_tolerance` (0.05 mm) for the "same tool re-measured" check | 0.05 mm | assumed |
| B9 | Spoilboard surface machine Z with the reference tool | Viewer placement, "below spoilboard" warnings | not set | blocked |
| B10 | Homing repeatability: home, note MPos at a fixed touch-off, re-home, repeat 5× | Whether the BitSetter reference survives re-homing (D9) | within 0.02 mm | assumed |

## C. Motion and stopping behavior (scrap wood, operator at the E-stop)

| # | Fact | Why it matters | Current value | Status |
|---|---|---|---|---|
| C1 | Feed hold deceleration time from typical feeds | Resume-point matching; the simulator has no deceleration | instant in sim | assumed |
| C2 | Soft reset after a **completed** hold keeps position (no `ALARM:3`) | Can a stopped job be resumed without re-homing (SPEC §10 Q4) | yes per GRBL source | sourced |
| C3 | USB unplugged mid-motion: does GRBL finish its buffer? | Power-cut / disconnect resume rules | yes in sim | assumed |
| C4 | Tool-change park position the operator can reach comfortably | Tool-change interceptor (§11.5) | front center, X −222, Y −440, Z −5 | assumed |
| C5 | BitRunner: time from `M3` until the router is at speed | Resume spin-up dwell (D15) | 2.0 s (matches the post's `G4 P2.0`) | assumed |
| C6 | Real reachable envelope per axis (jog to each limit slowly) | Envelope checks independent of `$130`–`$132` | 445 × 456 × 95 | assumed |

## D. Workflow checks (after A–C)

| # | Check | Pass when |
|---|---|---|
| D1 | BitZero Z on stock, then BitSetter reference, then re-measure the same tool | Z0 touch-off matches within 0.05 mm; the reference is stored |
| D2 | Tool change T02 → T21 through the bridge interceptor | The new tool touches the same stock top within 0.05 mm (paper test) |
| D3 | Stop mid-job, soft reset, resume from the plan | The cut continues without a step or gouge |
| D4 | Power-cycle mid-job, re-home, recover origin, re-measure, resume | Same as D3 |
| D5 | Claude probe through MCP (Motion armed) with the operator watching | Bounded, logged, shown in the console as `[claude]` |

## Sources
- BitZero V2 13 mm (11 + 2): Carbide 3D community, "BitZero V2 Z-Only Probe is 2mm off" and "Gsender, BitZero v2 probe settings" (z-probing at 13 mm).
- BitZero V2 58 × 58 mm: Duet3D forum, "Zeroing Macro for Carbide3D BitZero".
- Homing pull-off reading −3,−3,−3: Carbide 3D community, "Absolute x, y AND z with BitSetter on initialization".
- BitSetter location is user-set in Carbide Motion ("Use Current X/Y"): Carbide 3D BitSetter guide.

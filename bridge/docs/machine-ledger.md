# Machine ledger: what we still need from the real Shapeoko

Everything in the bridge has been proven only in unit tests and against `bridge/sim`. This ledger lists each fact about the real machine that the code currently **assumes**, why it matters, how to find out, and the value we use until then. Work through it on scrap wood with a hand on the E-stop. Update the status column and the profile (`bridge/profiles/shapeoko4-standard.json`, `verified: true`) as items are settled.

Status: `assumed` (best guess, in use), `sourced` (from documentation or community, not measured on this machine), `verified` (measured here), `blocked` (needs something first).

## A. Read-only capture (no motion; one session)

Run `uv run shapeoko-capture` from `../resurfacer` with Carbide Motion and UGS closed. That one run settles A1–A6.

| # | Fact | Why it matters | Current value | Status |
|---|---|---|---|---|
| A1 | `$130`/`$131`/`$132` max travel | Envelope checks; the simulator's `$$` | controller reads **845 / 850 / 95** (2026-11-16). The operator confirms a **standard** Shapeoko 4, so the profile keeps the 445 / 456 / 95 envelope. Soft limits (`$20=0`) and hard limits (`$21=0`) are off, so `$130`/`$131` only set the homing search distance | verified (values); envelope still per profile (C6) |
| A2 | `$110`–`$112` max rates | Rapid-rate estimate (`rapid_mm_min`) | **10000 / 10000 / 1000 mm/min**; accel `$120`–`$122` **500 / 500 / 270 mm/s²**. The profile's `rapid_mm_min` stays 5000 because it is also the MCP feed cap for Claude's moves | verified |
| A3 | `$10` status mask, `$22`–`$27` homing settings | MPos vs WPos parsing; the homing signature that invalidates the BitSetter reference (D9) | `$10=255` (MPos), `$22=1`, `$23=0`, `$24=100`, `$25=2000`, `$26=25`, `$27=3`. **MPos after homing −3, −3, −3** | verified (2026-11-16) |
| A4 | Banner, `$I` (`[VER:…]`, `[OPT:…]`), `$N0`/`$N1` | Simulator realism; resume back-up depth; startup blocks Carbide Motion may have left (`M56P0`?) | `Grbl 1.1f`, **`[VER:1.1f.20170131:]` `[OPT:VNPR,14,128]`: 14 planner blocks** (profile `planner_blocks: 14`; the bridge uses the live `$I` value). `$N` not read yet (needs Query) | verified except `$N` |
| A5 | Opening the port resets the controller (DTR) and comes up in alarm | Connect handling, "must re-home" rule | yes (seen 2026-11-15) | sourced |
| A6 | Pin letters in `Pn:` for the BitZero and the BitSetter, and whether `$5` inverts them | Probe-clear checks before every probe | **both `Pn:P`, `$6=0` (not inverted)**; clear when released | verified (2026-11-16) |

## B. Probing hardware (needs calipers, then gentle motion)

| # | Fact | Why it matters | Current value | Status |
|---|---|---|---|---|
| B1 | **BitZero V2 total thickness** (aluminum block + plastic base) | Z zero on the stock. A wrong value cuts too deep or air-cuts | **13.35 mm in the profile.** Calipers 2026-11-16: 13.1–13.45 mm (the base flexes). Paper test the same day: probing with 13.0 put Z0 at machine −50.720; with the BitZero removed the paper dragged at work Z −0.260, so the effective thickness is 13.0 + 0.26 + ~0.1 (paper) ≈ **13.36**. One paper test; confirm by re-probing with 13.35 (paper should drag at about +0.10) | measured once (2026-11-16) |
| B2 | BitZero V2 footprint | Simulator geometry; clearance moves around the block | 58 × 58 mm (from a published BitZero macro) | sourced |
| B3 | **BitZero V2 bore-center-to-corner offset** (X and Y, from the bore center to the inside corner of the locating lips). The bore *diameter* does not affect the result: probing both walls in X and Y finds the center for any bore or pin size; it only bounds the probe travel. | XY corner probing: corner = bore center − offset; an offset error moves the corner by exactly that error | unknown. Carbide 3D publishes no dimensions; Carbide Motion has them compiled in. **Calibrate on the machine:** put the block on a scrap corner, run Carbide Motion's corner probe and note G54, then (same placement) run the bridge's bore-center probe and note the center. The difference is the offset. Or measure with calipers. Simulator placeholder: 22 mm bore, 14.5 / 14.5 mm | blocked |
| B4 | Z-only placement | Which thickness applies in Z-only mode | **flat on the surface, full thickness (B1)**. The official guide says to "set the entire BitZero V2 on top of the surface to be measured". The corner cycle has the lips overhanging the stock edges with the pin inside the bore. Kit pins: 1/4 in and 1/8 in. | sourced |
| B5 | Probe failure reply on this firmware: `ALARM:4`/`ALARM:5`, `[PRB:…:0]`, and whether an `ok` follows | Command completion and error handling | `[PRB…:0]` then `ALARM:5`, no `ok` | assumed |
| B6 | **BitSetter machine X/Y** (spindle centered over the button) | Every tool measurement | **X −4.2, Y −448.2** (MPos X −4.200 Y −448.225 with the bit ~5 mm above the button, button not pressed; MPos Z −66.010) | verified (2026-11-16, by eye) |
| B7 | BitSetter trigger machine Z with a reference tool, safe start Z, and how far is safe to probe | Bounded probe travel | T02 at its original stick-out triggered at **−73.455** (manual, before a re-home) and **−73.485** (Measure BitSetter reference, after one); reference **22.405** with G54 Z −51.080. Start Z −5 and 90 mm of travel (to −95) cover it with margin | verified (2026-11-16) |
| B8 | BitSetter **repeatability**: 10 measurements of one tool, report spread | `repeat_tolerance` (0.05 mm) for the "same tool re-measured" check | **0.000 mm** spread: 3 manual probes at F100 (−73.455 ×3) and the routine's double measurement (−73.485 ×2); later −69.650/−69.655 across a power cycle. Z resolution is 0.005 mm. 10× still to do, but the 0.05 tolerance is ample | verified (5 samples) |
| B9 | Spoilboard surface machine Z with the reference tool (paper test on the spoilboard with the reference tool; with another tool, correct by the difference of the two BitSetter triggers) | Viewer placement (spoilboard and stock bottom height), "below spoilboard" warnings | not set; the viewer puts the spoilboard under the stock | blocked |
| B10 | Homing repeatability: home, note MPos at a fixed touch-off, re-home, repeat 5× | Whether the BitSetter reference survives re-homing (D9); the step a resume after a power cut can leave | first data 2026-11-16: one app restart + re-home moved Z by **0.03 mm** (BitSetter −73.455 → −73.485) to 0.05 mm (paper test −50.980 → −51.030); after a controller power cycle + re-home the BitSetter moved only **0.005 mm** (−69.650 → −69.655). Measuring each tool on the BitSetter cancels the shift (the reference is stock-to-BitSetter, both move together). 5× repeat still to do | partly measured |
| B11 | **Spoilboard corners in machine X/Y**: jog the bit (any tool, a few mm above) to the spoilboard's front-left and back-right corners and read MPos; note whether the BitSetter sits on or beside it | Viewer digital twin; the "stock reaches past the spoilboard" warning (profile `bed.spoilboard`) | assumed: front-left X −460 Y −432, back-right X +15 Y +15 (laid out around the envelope and the BitSetter, not measured) | assumed |
| B12 | **Frame layout**: machine X of the left and right Y-rail centres and machine Y of the front and back extrusions (tape measure from a corner you've touched off is enough, ±10 mm) | Viewer digital twin only (outline of the rails and extrusions; no motion check uses it) | assumed: Y rails at X −500 / +55, extrusions at Y −490 / +30 (profile `bed.frame`) | assumed |
| B13 | BitSetter keep-out radius: the housing around the button that stock must not cover | Viewer warning "stock overlaps the BitSetter" | 20 mm around `bitsetter.xy` (profile `bed.bitsetter_radius`) | assumed |

## C. Motion and stopping behavior (scrap wood, operator at the E-stop)

| # | Fact | Why it matters | Current value | Status |
|---|---|---|---|---|
| C1 | Feed hold deceleration time from typical feeds | Resume-point matching; how long `Hold:1` lasts | sim decelerates at the `$120`–`$122` placeholders (3000 mm/min on X: 0.125 s, 3.1 mm) | assumed |
| C2 | Soft reset after a **completed** hold keeps position (no `ALARM:3`) | Can a stopped job be resumed without re-homing (SPEC §10 Q4) | yes per GRBL source | sourced |
| C3 | USB unplugged mid-motion: does GRBL finish its buffer? | Power-cut / disconnect resume rules | yes in sim | assumed |
| C4 | Tool-change park position the operator can reach comfortably | Tool-change interceptor (§11.5) | front center, X −222, Y −440, Z −5 | assumed |
| C5 | BitRunner: time from `M3` until the router is at speed | Resume spin-up dwell (D15) | 2.0 s (matches the post's `G4 P2.0`) | assumed |
| C6 | Real reachable envelope per axis (jog to each limit slowly) | Envelope checks independent of `$130`–`$132` | 445 × 456 × 95 | assumed |
| C7 | BitRunner V2 switching: the router's speed is set only by its dial; S only has to trigger the BitRunner | Analyzer: `bitrunner-s-low` (error) / `bitrunner-s-unverified` (warn); RPM comes from the header's ROUTER DIAL, not S | **binary**: `M3 S10000` does NOT switch it on, `M3 S18000` does (`$30=24000`, so S10000 = 42% PWM). Profile `spindle.s_off_verified: 10000`, `s_on_verified: 18000`. **Several of the operator's job files use S16000, which is in between and not tested yet**: try `M3 S16000` (and S12000/S14000) to settle it. The SPINDLE ENABLE pendant must be lit. GRBL reports the spindle on (`A:S`) even when the BitRunner did not switch, so UGS cannot see it. If a VFD spindle is ever fitted, S becomes the speed again (profile `control: pwm`) | partly verified (2026-11-16) |
| C8 | Router spin-down after `M5` (seconds until the bit stops) | The tool-change prompt waits it out (profile `spindle.spin_down_s`) | **8 s assumed**: on 2026-11-16 the prompt appeared 1.2 s after `M5` while the bit still turned. Time it with a stopwatch from `M5` | assumed |

## D. Workflow checks (after A–C)

| # | Check | Pass when | Result |
|---|---|---|---|
| D1 | BitZero Z on stock, then BitSetter reference, then re-measure the same tool | Z0 touch-off matches within 0.05 mm; the reference is stored | **done 2026-11-16**: Z0 set by BitZero + paper correction (G54 Z −51.080), reference 22.405 stored with the new Machine → Measure BitSetter reference action; re-check after a restart showed the 0.03–0.05 mm homing shift (B10) |
| D2 | Tool change T02 → T21 through the bridge interceptor | The new tool touches the same stock top within 0.05 mm (paper test) | **marginal pass 2026-11-16**: air-cut file, bit reseated 3.835 mm longer (BitSetter −73.485 → −69.650, new G54 Z −47.245). Paper test with the longer bit, Z0 from the BitSetter only: tight drag at work Z −0.05, light ~0.00, expected +0.10, so Z carried within ~0.05–0.10 mm. Definitive check: two shallow pockets, one per tool, compare floors |
| D2a | At the tool-change prompt, the router is actually **off** (BitRunner relay open) and UGS shows the spindle off | | **pass 2026-11-16** (operator heard it stop; spindle off in every report while waiting). But the prompt came 1.2 s after `M5` while the bit still coasted: the routine now dwells out `spindle.spin_down_s` (C8) first |
| D3 | Stop mid-job, soft reset, resume from the plan | The cut continues without a step or gouge | not yet |
| D4 | Power-cycle mid-job, re-home, recover origin, re-measure, resume | Same as D3 | not yet (B10 bounds the step) |
| D5 | Claude probe through MCP (Motion armed) with the operator watching | Bounded, logged, shown in the console as `[claude]` | **pass 2026-11-16**: Claude moved to the scrap, lowered to work Z +3, went to the BitSetter and probed it (−69.650). Found: move/probe returned the pre-move position (fixed: `G4 P0` + fresh report) and status kept saying Idle while the controller was frozen (fixed: `controller_report`, refusal, `controller_silent` alert) |

### Incidents (2026-11-16)
- **Controller froze** (no status, no `ok`) right when the router was plugged straight into the wall: likely EMI. UGS still showed it connected; only a controller power cycle recovered it. G54 survived (EEPROM).
- **Jog into the stock**: eight quick 5 mm Z jogs drove the bit into the scrap; the Z motor **lost about 2 mm of steps** (a paper test read −1.96 instead of +0.10). Re-homing fixed it; a BitSetter re-measure (−69.650, unchanged) proved the bit hadn't slipped in the collet. After any crash: re-home, then re-measure on the BitSetter.

## Sources
- BitZero V2 13 mm (11 + 2): Carbide 3D community, "BitZero V2 Z-Only Probe is 2mm off" and "Gsender, BitZero v2 probe settings" (z-probing at 13 mm).
- BitZero V2 58 × 58 mm: Duet3D forum, "Zeroing Macro for Carbide3D BitZero".
- Homing pull-off reading −3,−3,−3: Carbide 3D community, "Absolute x, y AND z with BitSetter on initialization".
- BitSetter location is user-set in Carbide Motion ("Use Current X/Y"): Carbide 3D BitSetter guide.

- BitZero V2 setup guide (Carbide 3D, 2021-03-08, v1.0): guides.carbide3d.com/files/pdf/BitZero_V2_Shapeoko.pdf. Placement for the corner and Z cycles, probing pins. No dimensions.
# ugs-sim: GRBL 1.1 / Shapeoko 4 simulator over TCP

A simulated Shapeoko 4 (GRBL 1.1f) that UGS reaches with its **TCP connection driver**, so the
bridge can be developed and tested without the machine. It wraps resurfacer's `SimulatedGrbl`
(`../resurfacer`, read-only) in `ugs_sim.machine.ShapeokoGrbl` and serves it on loopback.

**Simulation is not hardware validation.** Placeholder values are listed under
[Assumptions](#assumptions-to-confirm-on-the-real-machine).

## Run

From `bridge/` (after `uv sync --all-packages`):

```sh
uv run ugs-sim --port 0 --time-scale 20
# LISTENING 127.0.0.1:52811
# CONTROL 127.0.0.1:52812
```

In UGS: connection driver **TCP**, port/host field `127.0.0.1`, "baud" field = the port number
(UGS builds `tcp://<host>:<baud>`). Only loopback addresses are accepted. One GRBL client at a
time; a second connection is closed immediately.

| Option | Default | |
|---|---|---|
| `--port`, `--control-port` | `0` | ephemeral; the chosen ports are printed |
| `--no-control`, `--stdin-control` | | no control port / read control commands on stdin |
| `--time-scale X` | `1` | simulated seconds per real second (`inf` = instant) |
| `--profile PATH` | `profiles/shapeoko4-standard.json` | or `$UGS_SIM_PROFILE` |
| `--capture PATH` | | resurfacer machine capture JSON: use its `$$` |
| `--setting N=V` | | override a `$` setting, e.g. `--setting 130=845` to reproduce the 2026-11-15 mismatch |
| `--tool MM` | `40` | tool tip below the spindle reference |
| `--tool-dia MM` | `6.35` | tool diameter (the cylinder that touches the BitZero) |
| `--bitsetter-top-z`, `--stock-top-z` | `-105`, `-95.6` | tool-tip Z (machine) of the setter / raw stock top |
| `--m6 error\|ignore` | `error` | stock GRBL 1.1 answers `M6` with `error:20` |
| `--no-connect-reset` | | connect without the DTR reset (profile `connect_resets_controller`) |
| `-v` / `-vv` | | log connections / every byte to stderr |

## Control channel

Text lines on the control port (`nc 127.0.0.1 <control-port>`) or stdin; one reply line each
(`ok ...` or `error: ...`).

| Command | Effect |
|---|---|
| `state` | JSON: state, MPos, physical position, homed, G54, tool, probe pin, buffers, `rx_overflow` |
| `tool [<length_mm>]` | operator swaps the bit (changes stick-out; BitSetter/BitZero readings follow) |
| `tooldia [<mm>]` | tool diameter for BitZero contact (default 6.35) |
| `alarm <n>` | raise `ALARM:<n>` (1 and 2 are critical: `[MSG:Reset to continue]`) |
| `door [open\|close]` | safety door: `Door:1` (hold, `~` ignored) then `Door:0` (`~` resumes) |
| `probe on\|off` | hold the probe input (BitZero clip touched, BitSetter pressed by hand) |
| `bitzero on\|off` | BitZero present on the stock or removed |
| `bitzero` | JSON: mode (`follow`/`placed`), geometry, `bore_placeholder` |
| `bitzero place [<x> <y>]` | place the BitZero V2 block with its lips hooked over machine XY `<x> <y>` (default: the stock's front-left corner); enables XY probing |
| `bitzero bore <dia> <offset> [<offset_y>]` | bore diameter and bore-center-to-corner offset (ledger B3) |
| `bitzero follow` | back to the default plate that is assumed under the tool wherever it Z-probes the stock |
| `unplug` | drop the TCP client like a pulled USB cable; queued motion keeps running |
| `reset` | hardware reset (power cycle) |
| `timescale <x>` | change the simulation speed |
| `shutdown` | stop the simulator |

## What is modeled

From resurfacer's `SimulatedGrbl`: 128-byte RX buffer (overflow is recorded, not silently
fixed), 15-block planner with `ok` on queue, sync of `$`, G4, G10, G38, M0-M2/M30, M3/M5,
homing ($H: about 2 s plus travel, ends at `-$27` = -1,-1,-1), `$X`, `$J=` jogs with soft-limit
`error:15`, jog cancel 0x85, feed hold `!` (`Hold:1` then `Hold:0`) and resume `~`, soft reset
0x18, `$$ $# $G $I $N`, G54 via `G10 L2/L20 P1`, `G38.2/G38.3` with `[PRB:...]` (extended below), probe
alarms 4 (already triggered) and 5 (no contact), status `<...|MPos|Bf|FS|Pn:P|WCO>`.

Added in `ugs_sim.machine`:

- Profile: `$130-$132` from the envelope (445/456/95), BitSetter at the profile XY (±4 mm switch
  pad), BitZero plate thickness when the profile has one.
- Physics: probe pin `P` when the tool **tip** (spindle Z minus tool length) touches the BitSetter
  or the BitZero plate on the stock. A tool change by `tool <mm>` shifts the BitSetter trigger by
  the length delta.
- Probing in any direction: `G38.2`/`G38.3` (toward) and `G38.4`/`G38.5` (away) with any of
  X, Y, Z. Contact is computed exactly along the straight move (no time stepping error);
  `[PRB:x,y,z:1]` reports the contact point and the machine stops there. Failure as for Z:
  `G38.2`/`G38.4` give `[PRB:…:0]` + `ALARM:5`, `G38.3`/`G38.5` give `[PRB:…:0]` + `ok`.
  `G38.2/3` with the pin already closed, or `G38.4/5` with it open, give `ALARM:4`.
  A zero-length probe gives `error:20`.
- **BitZero V2** (`ugs_sim.bitzero`), two modes:
  - `follow` (default, unchanged): a plate `plate_thickness` (13 mm) high is assumed under the
    tool whenever a probe starts over the stock rectangle. Z only in practice.
  - `placed` (`bitzero place`): a 58 × 58 mm aluminum block, 13 mm above the stock top, its
    front/left faces 2 mm proud of the registration corner (lip, placeholder), with a through
    bore. The tool is a vertical cylinder of `tooldia`. Contact = tip at or below the block top
    and the tool disc overlapping aluminum (the square minus the bore): Z on the top (including
    a tool overhanging an edge), XY against the bore wall, XY against the outer faces. Over the
    bore a Z probe falls through (the stock is not conductive) and fails.
  - Bore **placeholders**: 22 mm diameter, center 14.5 mm in from the corner on X and Y, used
    while the profile's `bitzero.bore_diameter` / `bore_center_to_corner` are `null` (ledger
    B3). Filling those in the profile replaces them.
  - A reference XYZ corner routine, `ugs_sim.testing.bitzero_xyz_corner`: Z on the top, X± and
    Y± inside the bore, corner = bore center − offset, Z0 = top − 13 mm. The chord midpoint
    makes the center independent of the tool diameter; the tests recover the configured corner
    within 0.01 mm for 1 to 12.7 mm tools.
- **Feed-hold deceleration**: on `!` (or the door) the machine keeps moving along the planned
  path and slows at the acceleration GRBL would use along that move (the smallest
  `$12x / |direction component|`), so `Hold:1` lasts `v / a` simulated seconds and covers
  `v² / 2a` (3000 mm/min on X at 400 mm/s²: 0.125 s, 3.125 mm), with `FS:` showing the falling
  speed. Then `Hold:0`. `~` during `Hold:1` is ignored (GRBL resumes only a completed hold); a
  repeated `!` doesn't restart it; `0x18` during `Hold:1` is a reset in motion (`ALARM:3`),
  after `Hold:0` it keeps position. Scales with `--time-scale` like everything else.
- Connect = DTR reset (profile `connect_resets_controller`): banner,
  `[MSG:'$H'|'$X' to unlock]`, Alarm. Reported MPos restarts at 0,0,0 but the gantry stays where
  it was physically (`state` shows both), so probing after `$X` without homing hits the
  surfaces at shifted coordinates. `$H` re-establishes the true position.
- Real-time extras: 0x84 door, 0x90-0x9D overrides (feed override scales feed motion; rapid and
  spindle are reported only), 0xA0/0xA1 coolant; other bytes >= 0x80 are dropped. `Ov:` and
  `A:` fields in status.
- Words used by the job files and UGS: `T` (shown in `$G`), `G17-G19 G40 G49 G61 G80 G91.1`
  (accepted, ignored), `M4` as `M3`, `M7/M8/M9`, `G2/G3` as a straight chord (time and
  path are approximate), `$RST=#`, `$G` without waiting for the planner.
- Critical alarms 1/2 (soft limit on a G-code move): `[MSG:Reset to continue]`; all lines are
  ignored until 0x18.

### GRBL reset rules (as resurfacer's simulator assumes them)

| Event | Position | Alarm | Kept | Lost |
|---|---|---|---|---|
| 0x18 while idle, or after a completed feed hold (`Hold:0`) | kept | none | G54-G59 (EEPROM), settings | modal state (back to G0 G54 G17 G21 G90 G94 M5 M9 T0), overrides, door |
| 0x18 during motion or homing | kept as reported, flagged lost | `ALARM:3`, then lock message | same | same; must re-home |
| connect (DTR) / power / `reset` | MPos 0,0,0, physical unchanged | lock (`$22=1`) | G54-G59, settings | everything else; must re-home |
| TCP drop (`unplug`) | machine keeps executing queued motion | — | — | output while unplugged |

### Not modeled

Acceleration outside feed holds (moves start and stop at full speed; jog cancel `0x85` stops
at once), probe overshoot (the machine stops exactly at contact; real GRBL decelerates past it
and reports `PRB` at the trigger), step timing, arcs as arcs, the BitSetter as anything but a
point switch (±4 mm, tool center), the stock as an obstacle (a tool passes through wood), the
BitZero's plastic base and lips as solids, a tilted or misplaced block, `G20` inches, `G28/G30`, `G92`,
`G55-G59` selection, `$C` check mode, `$SLP`, hard limits and limit pins (`Pn:XYZ`), spindle
speed, material removal, BitSetter plunger over-travel, `$10` report masks (always MPos +
`Bf`).

## Tests

```sh
uv run pytest sim/tests                     # ~10 s, real loopback sockets, ephemeral ports
UGS_SIM_JAVA=1 uv run pytest sim/tests      # + UGS GrblController check (~1 min, compiles ugs-core)
```

The 26 000-line job file test runs when `bridge/testdata/nc/dr_ryan_complete_T02_then_T21.nc`
exists (gitignored; see `bridge/TESTDATA.md`). It streams in about 1 s at `time_scale=inf`.

### UGS compatibility check

`sim/scripts/ugs-compat.sh [file.nc] [time-scale]` compiles `ugs-core` offline into this
checkout's `target/` (nothing is installed into `~/.m2`), starts the simulator on ephemeral
ports, and runs `sim/java/UgsSimCheck.java`: ugs-core's own `GrblController` +
`GrblCommunicator` + `TCPConnection`, headless. It connects (expects Alarm after the DTR
reset), homes, jogs, probes the BitSetter (UGS sees `PRB` and `Pn:P`), changes the tool through
the control port and checks the 12.5 mm delta, sets G54, streams (300 lines by default, or the
given file with `M6` lines removed) and checks every line completed with no error, alarm or RX
overflow. It disables UGS's mDNS listener (which the TCP driver would otherwise start on every
interface), never touches `SettingsFactory`, and runs with `user.home` pointed at an empty temp
dir that must stay empty.

## Assumptions to confirm on the real machine

Each needs the resurfacer machine capture (`../resurfacer/docs/machine-capture.md`) or a
supervised hardware check:

- `$$`: the simulator uses resurfacer's placeholders plus the profile's 445/456/95; the machine
  read `$130/$131` = 845/850 on 2026-11-15. Steps/mm, rates, `$27` pull-off and `$10` are guesses.
- Banner and build info: `Grbl 1.1f ['$' for help]`, `[VER:1.1f.20170801:...]`, `[OPT:V,15,128]`.
  Carbide's firmware may report a different build string or buffer sizes.
- That opening the USB port resets the controller and leaves it alarm-locked (profile says yes).
- Both BitZero and BitSetter on pin `P`, not inverted.
- Probe failure: the simulator prints `[PRB:...:0]` then `ALARM:5` and **no** `ok` (resurfacer's
  assumption; UGS also treats the ALARM as completing the command). Stock GRBL source suggests an
  `ok` may follow the alarm; the capture should settle it.
- `M6` answered with `error:20` (stock GRBL 1.1); Carbide's build may differ.
- Geometry placeholders: tool length 40 mm, BitSetter top at tip Z -105, stock top -95.6,
  stock rectangle X -420..-180, Y -420..-325. BitZero V2 thickness 13 mm (sourced, B1),
  58 × 58 mm (sourced, B2); **bore 22 mm and center 14.5 mm from the corner are placeholders
  (B3)**, the 2 mm lip is a guess.
- Feed hold uses the `$120-$122` placeholders (400/400/200 mm/s²) until the capture (A, C1).

# ugs-bridge viewer (offline and live)

A Three.js toolpath viewer for G-code jobs (SPEC §6, PLAN package D). It's a static web app:
plain ES modules plus an import map, with no build step. Three.js is vendored in `vendor/`.
The plugin will later serve this folder as-is at `/view/`.

![sample job, iso view](docs/screenshots/sample-iso.png)

![digital twin: bed, BitSetter, stock cut up to line 200](docs/screenshots/sample-twin-iso.png)

## Open it

ES modules don't load from `file://`, so serve the folder over HTTP. Serve `bridge/` (not only
`bridge/viewer/`) so the machine profile and the public sample are reachable:

```sh
cd bridge && python3 -m http.server --bind 127.0.0.1 0   # prints the port
# open http://127.0.0.1:<port>/viewer/
```

Then drop a `.nc` file on the page or use **Open…**. Add the `<program>.job.json` sidecar (and,
if you like, a machine profile JSON) in the same selection. The **Sample job** button loads
`bridge/testdata-public/sample-sign.nc`.

URL parameters, for links and headless screenshots:
`?file=<url>&sidecar=<url>&profile=<url>&view=top|front|iso|machine&line=<n>&wco=x,y,z&section=<workZ>`.
With `file=` only, the viewer also tries the sidecar next to it: `<stem>.job.json`, then
`<file>.nc.job.json` (D12).

## Live mode

Served by the plugin, UGS opens `/view/#vt=<token>`. For development, serve the viewer anywhere
and point it at a bridge: `?live=http://127.0.0.1:<port>#vt=<token>` (loopback only; the bridge
must allow the page's origin with CORS). The viewer token is read-only and isn't affected by the
operator's lock for Claude.

On connect the viewer reads `GET /status`, `/profile`, `/file` + `/file/content`, `/origins` and
`/job/state` (header `Authorization: Bearer v.<token>`), then follows `WS /events?since=…&vt=<token>`.
Every record goes through a pure reducer (`src/live/state.js`) and the scene redraws what changed
once per frame:

| Shown | From |
|---|---|
| Live tool at its machine position, with a trail of the actual path (magenta) | `status` MPos |
| Work-zero axes and the toolpath placed at the machine's work offset | `status` WCO, `origin` records |
| Planned position at the current line (cyan) and stream progress on the scrubber | `tx.file_line` sent, `rx` ok acked, status `Ln` when reported, `/job/state` line_done |
| Probe hits with machine X/Y/Z and the G54 Z at the time | `event probe`, `[PRB:…]` rx lines (deduplicated) |
| Banners: tool change (interceptor step), alarm (GRBL text), feed hold, a GRBL error on a file line | `tool`, `event interceptor`, `ALARM:n`, `event alarm`, `status` state, `rx error:n` |
| Link state: online, offline (backoff countdown), locked or refused, unauthorized; controller disconnected; no program loaded | the connection, `session` records, `/status` |
| A new program loaded in UGS is fetched and drawn again | `file loaded` with a new SHA-256 |

Reconnects back off (0.5, 1, 2, 4, 8, 10 s), start with a fresh `GET /status` and resume the event
stream from the last applied `seq`. "Follow stream" keeps the scrubber on the current line (dragging
it stops following). "Dim program" fades the planned path so the trail stands out.

The live integration bench (`test/live/`) runs the viewer in headless Chrome against the fake bridge
from `bridge/mcp` with a simulated GRBL: see "Tests".

## Features

| | Status |
|---|---|
| Open by drag and drop or the file picker (`.nc` + optional `.job.json` + optional profile) | done |
| Machine envelope, homing corner, spoilboard, BitSetter, tool-change park (from the profile) | done |
| Bed twin: spoilboard rectangle, Y rails, front/back extrusions, BitSetter keep-out ring, FRONT marker (profile `bed`, **assumed** until ledger B9/B11–B13 are measured) | done |
| Stock placed at its machine position: work offset (live G54) + stock box from the sidecar/header | done |
| Placement warnings: stock over the BitSetter, past the spoilboard, cuts outside the stock | done |
| Material removal: the stock is cut away up to the scrub position (live: up to the stream's current line) | done |
| Time from the bridge's planner estimate (`GET /file/estimate`), else rough distance ÷ feed (labelled); per-op duration and end time, per-tool totals, tool-change times | done |
| Live ETA (`eta` in `/job/state` and `eta` records): "Now: T21 contour · 4 min left in op · job ≈ 18:42 · next tool change in 12 min", clock end per op | done (live mode) |
| Phone width (390 px): panel under the view, wrapped timeline, ETA pill over the view | done |
| Work-zero (G54) axes. The offset is an editable input, labeled *assumed* | done |
| Stock box from the sidecar or the `(STOCK: …)` header | done |
| Toolpath colored by operation; rapids dashed; plunges in yellow | done |
| Tool-change points with tool id and name (`M6 → T21 …`, line number) | done |
| Clearance plane (dashed, from `(CLEARANCE: …)` or the sidecar) | done |
| Timeline scrubber by line or by estimated time, play, ←/→ to step (Shift: ×100) | done |
| Current tool shown at the scrub position (cylinder of the tool's diameter) | done |
| Legend: show or hide each operation (name, tool, line range); click to scrub to its end | done |
| Section plane at Z (clips the toolpath and the stock) | done |
| Top and front (orthographic), iso and machine (perspective) views | done |
| Hover or click a segment: line number, op, tool, feed, from→to, ±3 lines of G-code | done |
| PNG snapshot, glTF (`.glb`) export | done |
| Warnings in red: cuts below the floor, moves outside the envelope (clickable list) | done |
| Fixtures (sidecar `fixtures`) drawn as boxes; tabs (sidecar `tabs`) as markers | drawn, not checked |
| Live tool, trail, planned position, probes, progress, banners, live work offset | done (live mode) |
| Fixture-crossing warnings, cutting the stock along the actual trail, resume-path overlay | later / J4 |

**Floor** for the "below" warning: sidecar `spoilboard_limit_z`, else a header floor
(`through cut Z…` in `(STOCK: …)`, or `(FLOOR: Z…)`), else the stock bottom.

**Work offset.** Offline there's no WCO from the machine. The default puts the stock's
front-left corner 20 mm in from the front-left corner of the spoilboard (or of the envelope,
whichever is further in), with the stock bottom on the
profile's `spoilboard_z_machine` when it is set, else on an assumed spoilboard 10 mm above the
lowest Z. Envelope warnings and the spoilboard position depend
on it, and the panel says so. `G53` moves are converted with this offset (the program is
re-parsed when it changes).

**Time estimate.** When the bridge answers `GET /file/estimate` (the plugin's GRBL planner model:
acceleration, junction speeds, the controller's `$` settings, tool-change time) the viewer uses its
per-line times for the scrubber, the legend and the readout, labelled *planner*. A file with a
different line count is ignored. Otherwise (offline, older plugin, 404) it falls back to path
length at the programmed feed, rapids at `(Rapids: N/min)` from the header or 5000 mm/min, plus
`G4` dwells, labelled *rough (distance ÷ feed)*: no acceleration, so short-segment carving files
come out too short. dr_ryan comes out at 61.5 min rough against the post's 62.0 min.

**Material removal.** Ported from the operator's `cnc-sim-viewer` skill (its licence allows
copying and modification): a heightfield of the stock top in a Web Worker (`src/sim/`), 0.25 mm
cells (coarser for big stock, `?cell=0.5` to override), exact flat end-mill sweep and a sampled
ball profile, keyframe snapshots every K segments so scrubbing back is fast. A tool with an
unknown diameter or a shape we can't draw (V-bit, chamfer, engraving, tapered, drag) cuts nothing
and the Job panel names it: a wrong picture is worse than none. The skill's IndexedDB cache isn't
ported (our jobs simulate in well under a second). It's a preview, not a verification.

**Bed twin.** The profile's `bed` section (spoilboard rectangle, rails, extrusions, BitSetter
keep-out radius) is laid out from published Shapeoko 4 Standard dimensions around the measured
BitSetter and is marked `verified: false`; the labels say *assumed*. Ledger B9 and B11–B13 say how
to measure it.

## Code layout

```
index.html, styles.css      page, import map
src/main.js                 entry: profile lookup, URL parameters, window.ugsViewer
src/gcode/   (pure: no DOM, no Three.js; runs in Node)
  parser.js                 GRBL interpreter → flat segment table (typed arrays) in work coords
  arcs.js                   G2/G3 centers (I/J/K, R), chord-tolerance tessellation, helix, planes
  header.js                 post-processor header and marker parser
  job.js                    job model: sidecar > header > toolpath; ops, stock, floor, warnings
src/scene/                  Three.js
  viewer.js                 renderer, cameras, controls, views, section, picking, PNG/glTF
  toolpath.js               merged LineSegments per (operation × cut|plunge|rapid), scrub, pick
  machine.js                envelope, bed (spoilboard, rails, extrusions), BitSetter, park, FRONT
  stocksim.js               simulated stock: heightfield grid displaced from a float texture
  labels.js                 constant-pixel-size sprite labels
src/data/
  sources.js                OfflineFileSource, UrlSource, LiveSource, sidecar names and check
  profile.js                profile normalize (incl. bed), built-in fallback, default work offset
  estimate.js               /file/estimate → per-line times, rough fallback, op/tool times, ETA text
src/sim/     (pure: runs in Node and in the worker)
  heightfield.js            material-removal heightfield, snapshots, dirty rectangles
  simjob.js                 program + job → simulation input (stock-local), tool models, skips
  sim-worker.js             module worker around heightfield.js
src/twin/placement.js       stock machine box, BitSetter/spoilboard/outside-stock checks (pure)
src/live/    (pure: runs in Node)
  state.js                  reducer: /status, /job/state and log records → live viewer state
  connection.js             authenticated GETs, WS /events, reconnect with backoff, link states
src/scene/live.js           live tool, trail, planned marker, probe hits
src/ui/app.js               panel, legend, scrubber, hover, export
src/ui/live.js              live controller: connection → reducer → scene and panel; estimate, ETA
src/ui/sim.js               drives the simulation worker from the scrubber
test/*.test.js              node --test
test/live/                  live integration bench (fake bridge + headless Chrome)
vendor/three-0.170.0/       pinned Three.js (see vendor/README.md)
```

The resume path (J4) can be a further layer that reuses `ToolpathLayer`'s merged-geometry
pattern, next to the live layers in `src/scene/live.js`.

## Tests

```sh
cd bridge/viewer && node --test test/*.test.js     # or: npm test
```

The tests cover:

- **Parser:** modal state, F on G0, unknown start positions, `G53` with the WCO, `G91`/`G90`,
  `G10`/`G92`/`G28` not drawn as moves, `M6`, plunge detection, the time estimate.
- **Arcs:** G2/G3 with I/J, full circles, chord tolerance, helices, R± arcs, G18/G19, G90.1, inch.
- **Headers:** header variants, op segmentation (D11: the tool-change block is in no op; an M6
  before an op's first move stays in it), the op tool from the first move, `M6` without `T`, marker
  vs loaded tool, sidecar names (D12), profile `spoilboard_z_machine`, the sidecar on the public
  sample.
- **Real files** in `bridge/testdata/nc/` (gitignored, skipped when absent). The dr_ryan file
  must give 9 ops, T02×4 then T21×5, with the ranges of post-headers.md §4 (OP 04 ends at 17636,
  the T21 `M6` at 17646 is in no op), header cut extent X 7.6..232.4 Y 7.6..87.4 Zmin −19.7, and
  the parsed path plus the cutter radius must reproduce that extent within 0.05 mm. Every file
  must parse, each op's tool must match the tool loaded at its first move, and no op may contain
  an `M6` after its first move. (All 16 corpus files give the same op ranges as the Python
  reference, `ugs_mcp.gcode.job.operations`.)
- **Live state** (`test/live-state.test.js`): the reducer on synthetic record streams (position,
  WCO, trail, stream progress, probes, origins, tool change, alarms, disconnect, lock, snapshots,
  100k records in ~20 ms) and the connection (auth header, `vt`, backoff, link states).
- **Simulation** (`test/sim.test.js`): a straight cut removes exactly the cells within the cutter
  radius (24 cells for a 6 mm cutter at 0.25 mm), nothing above the stock, scrubbing forward,
  back and to the end matches the full build, a ball groove follows its profile, cell coarsening,
  tool models and skipped tools.
- **Twin and time** (`test/twin.test.js`): stock machine box, BitSetter/spoilboard/outside-stock
  warnings, the bed section, the `/file/estimate` mapping and its fallback, op/tool times, the
  ETA line and the reducer's `eta` handling.

**Live integration** (needs `uv` and Google Chrome; playwright-core is not a repo dependency):

```sh
npm install --prefix /tmp/pw playwright-core
cd bridge && PLAYWRIGHT=/tmp/pw/node_modules/playwright-core/index.mjs node viewer/test/live/drive.mjs /tmp/live-out
```

`test/live/fake_live.py` starts `ugs_mcp.testing.fake_bridge` on 127.0.0.1 (ephemeral port, temp
`UGS_BRIDGE_HOME`, viewer token, the viewer at `/view/`) with a simulated GRBL, and takes commands
on stdin (home, probe, stream lines with a simulated tool-change interceptor, alarm, lock,
disconnect, stop/start). `drive.mjs` opens the viewer in headless Chrome, checks the live state
after each step and saves screenshots. It uses dr_ryan when `bridge/testdata/nc/` has it, else
the public sample. The bench also answers `GET /file/estimate` with a synthetic estimate (0.4 s per
move, 60 s per tool change) and emits `eta` records while streaming, so the run checks that the
viewer switches to the planner estimate and shows the ETA pill.

## Performance

These numbers are for dr_ryan (26 781 lines, 26 647 segments, 9 ops), measured in headless
Chrome with SwiftShader, which is software GL:

| Step | Time |
|---|---|
| Parse, job model and geometry build | about 55 ms |
| Frame | 0.5 ms (44 draw calls) |
| Scrub update (binary search + `setDrawRange`) | 0.03 ms |
| Hover raycast | about 1.1 ms |

The design choices behind these numbers:

- No per-segment objects: one merged `LineSegments` per operation and class.
- Render on demand.
- Arcs are tessellated to a 0.01 mm chord tolerance.

## Header patterns relied on

These are the post-processor comment patterns the viewer reads. Reconcile them with
`bridge/docs/post-headers.md`. Each is matched on the comment text inside `( … )`. Nested parens
are allowed. Where a field appears more than once, the first occurrence wins.

| Pattern | Example | Used for |
|---|---|---|
| `FILE: <name>` | `(FILE: dr_ryan_complete_T02_then_T21.nc)` | title |
| `PROJECT: <text>` | | info panel |
| `STOCK: … A x B x C …` | `(STOCK: at least 240 x 95 x 19.4 mm hard maple. …, through cut Z-19.7)` | stock size. `at least`, `>=` and `raw` mark it approximate |
| `STOCK: … A x B, raw t1-t2 thick` | `(STOCK: hard maple blank 298 x 184, raw 18.3-20.5 thick, in wood jig)` | thickness = t2 |
| `through cut Z<z>` inside STOCK | | floor for the "below" warning |
| `FLOOR: Z<z>` | `(FLOOR: Z-13.65 = 0.40mm INTO the spoilboard, by design)` (FreeCAD files) | floor |
| `ZERO …: <text>` / `WORK ZERO: <text>` | `(ZERO: X0 Y0 = stock FRONT-LEFT corner, Z0 = RAW STOCK TOP.)` | stock origin (`front`/`back`, `left`/`right`/`center`, `top`/`bottom`) when the text says *stock*. Otherwise the origin is *assumed*: on top of Z0 if every cut is above Z0 (jig-top zero), else front-left-top |
| `CLEARANCE: … Z+<z>` | `(CLEARANCE: every rapid/retract at Z+5 [work]. …)` | clearance plane |
| `UNITS: mm\|in` | `(UNITS: mm [G21] …)` | not used: G20/G21 in the code decide |
| `TOOL …: T<n> <desc>` | `(TOOL [FIRST]: T02 2-flute downcut spiral 1/4 in, Whiteside RD2100   feed 1778 / plunge 508 mm/min …)`, `(TOOL AFTER THE MID-FILE TOOL CHANGE: T21 …)`, `(TOOL: T21 …)` | tool table |
| `<n>. T<n>  <desc>` in a tools list | `(  1. T01  3-wing surfacing bit 1 in    Whiteside 6210   LOADS AT ~min 1 …)` plus indented continuation lines with `feed … / plunge …` | tool table |
| `INSTALL IN COLLET: T<n> <desc>`, `LOAD: T<n> <desc>`, `GEOM: dia <d> mm   flute <f> mm`, `BIT: <maker model>` | tool-change blocks | tool table, diameter |
| `   T<n>   <d> mm (…)  feed …` | FreeCAD header `(   T3    6.350 mm (0.2500 in)   feed  2032 mm/min   18000 RPM)` | tool table |
| `     tool T<n> <desc>, feed … / plunge …` | detail line under an op marker | tool table |
| `OPERATIONS:` list items `<n>. [T<n>] <name> - <desc>` or `<n>. [T<n>] ~min <m>  <name>` | `(  5. [T21] clear_hi - Clear what T02 could not reach …)` | op descriptions and tools for colon-style markers |
| **op marker, pipe style** `---- OP <nn> \| T<nn> \| <name> \| <desc> ----` | `(---- OP 05 \| T21 \| clear_hi \| Clear what … ----)` | operation start |
| **op marker, colon style** `---- OP <n>: <name> ----` | `(---- OP 3: lid recess ----)` | operation start. The tool is the planned op's tool, else the tool loaded at the op's first move |
| FreeCAD `Begin operation: <name>` | | operations, each to the next `Begin`. Those without motion (tool controllers, `Fixture`) are dropped. `TC_T03_6.35mm_…` gives a diameter |
| `CUT EXTENT …: X a..b  Y c..d  Zmin z` | `(CUT EXTENT incl cutter: X 7.6..232.4  Y 7.6..87.4  Zmin -19.700)` | info and tests |
| `EST RUNTIME: <m> min` | | info (compared with our estimate) |
| `Rapids: <rate>/min` | `(Rapids: 5000.0/min)` (Fabex) | rapid rate for the time estimate |

**Operation ranges** follow `bridge/docs/post-headers.md` §4 (decision D11). An op runs from its
marker line to the line before the next marker, or before the first `M6` after its first motion
line, whichever comes first; then its end moves back over blank and comment-only lines. So the
tool-change block between two ops belongs to neither (dr_ryan: OP 04 is 13385–17636, the T21 `M6`
at 17646 is in no op, OP 05 starts at 17651). The op's tool is the marker's, else the operation
list's, else the tool loaded at the op's first move; a marker tool that differs from the loaded tool
is a warning. FreeCAD `Begin operation:` ops run to the next `Begin`. An `M6` without a `T` word on
the line or since the previous `M6` is a warning.

**No markers at all.** One op per `M6` segment (motion before the first `M6` is its own segment,
segments without motion are dropped), or the whole file is one op. A sidecar's `operations`
replace the header ops entirely.

## Open questions

1. The sidecar isn't reachable in live mode (the API serves the program, not its
   `.job.json`), so live jobs use the post headers. Serve it as `GET /file/sidecar`?
2. `?vt=` carries the raw token (the header form is `Bearer v.<token>`); the fake bridge accepts
   both `vt=<token>` and `vt=v.<token>`. E should pick one and say so in api.md.
3. A refused WebSocket upgrade (HTTP 423/401) looks like a plain close (1006) in a browser. The
   viewer then asks `GET /status` and shows "refused"; closing with 4423/4401 after accepting
   gives a clearer state.
4. The dev mode (`?live=`) needs CORS on the bridge (GET, `Authorization`) for the page's
   origin. The plugin-served `/view/` doesn't.
5. The profile has no BitSetter top Z, so the BitSetter is drawn at an assumed height.
   `spoilboard_z_machine` is honored when set.
6. Multi-file jobs on a jig (step20/30/50) zero on the jig, not the stock. The stock placement
   is a guess there; a sidecar `stock.origin`/offset would fix it.

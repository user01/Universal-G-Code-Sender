# Post-processor headers → job data (grammar v1)

The operator's post (`post/user.py`, CAM by Fabex) writes comment headers that describe the job. When a file has no sidecar (`schema/job-sidecar.schema.json`), every component reads these headers instead and produces the **same structure as the sidecar**. Three parsers implement this document: Python (`bridge/mcp/src/ugs_mcp/gcode/headers.py`, `job.py`; the reference), Java (J1) and JavaScript (viewer). Keep them in step: change this file first.

Everything is best effort. A file from another post (for example FreeCAD's `grbl_post`) yields fewer fields, never an error.

## 1. Lines and comments

- Lines are numbered from **1**. All line numbers below (operations, tool changes) are 1-based file lines, as UGS and editors show them.
- A comment is the text inside `( … )`, or after `;` to the end of the line. Parentheses may **nest**: `(T3 6.350 mm (0.2500 in) feed 2032)` is one comment whose text is `T3 6.350 mm (0.2500 in) feed 2032`. (UGS strips such comments whole; GRBL itself would end the comment at the first `)`, so analyzers flag nesting as a note.)
- The **header block** is every line from the top of the file up to, not including, the first line that contains code (anything outside comments). Header keys (§2) are read only from the header block. OP markers (§4) and `Rapids:` are read anywhere in the file.
- Inside the header block, a comment text is **indented** if its first character is whitespace (`(  1. T03 …)`). Indented comments continue the previous key or list (§2.1). A non-indented comment that matches no key ends the current list.

Notation: `NUM` = `[-+]?\d+(\.\d+)?`. Patterns are regular expressions matched against the comment text with leading/trailing whitespace removed (except where indentation matters). Keys are upper case and matched case-sensitively; values are matched case-insensitively.

## 2. Header keys

| Key line (regex on the comment text) | Example | Produces |
|---|---|---|
| `^FILE:\s*(.*)` / `^PROJECT:\s*(.*)` | `(PROJECT: free-standing sign …)` | `title` (PROJECT wins) |
| `^STOCK:\s*(.*)` | `(STOCK: at least 240 x 95 x 19.4 mm hard maple. Z0 = RAW top, …, through cut Z-19.7)` | `stock` (§2.2), maybe `spoilboard_limit_z` |
| `^((?:WORK\s+)?ZERO\b[^:]*):\s*(.*)` | `(ZERO ONCE [with T02 in the collet]: X0 Y0 = FRONT-LEFT corner of the STOCK, Z0 = TOP of the RAW stock. …)` | `zero` (§2.3) |
| `^UNITS:\s*(.*)` | `(UNITS: mm [G21]  ABSOLUTE [G90]  work offset G54)` | `units`: `in` if the value contains `G20`, `mm` if `G21` |
| `^CLEARANCE:\s*(.*)` | `(CLEARANCE: every rapid/retract at Z+5 [work]. Tool-change park is G53 machine Z-5.)` | `clearance_z_work` = first `\bZ\s*(NUM)`; `tool_change_park_z_machine` = `park[^.]*?\bZ\s*(NUM)` |
| `^FLOOR:\s*(.*)` | `(FLOOR: Z-13.65 = 0.40mm INTO the spoilboard, by design)` | `spoilboard_limit_z` = first `\bZ\s*(NUM)` |
| `^TOOLS\b[^:]*:\s*$` | `(TOOLS IN THIS FILE, IN ORDER [stage these before starting]:)`, `(TOOLS IN RUN ORDER:)` | starts the tool list (§2.4) |
| `^TOOL\b(?!S)(?!\s+CHANGE)[^:]*:\s*(T\d+\b.*)` | `(TOOL [FIRST]: T02 2-flute downcut spiral 1/4 in, Whiteside RD2100   feed 1778 / plunge 508 mm/min …)`, `(TOOL AFTER THE MID-FILE TOOL CHANGE: T21 …)`, `(TOOL: T21 …)` | one tool (§2.4); indented lines after it continue it |
| `^OPERATIONS\b[^:]*:\s*$` | `(OPERATIONS:)`, `(OPERATIONS [with estimated start times]:)` | starts the operation list (§2.5) |
| `^(CUT EXTENT\b[^:]*):\s*(.*)` | `(CUT EXTENT incl cutter: X 7.6..232.4  Y 7.6..87.4  Zmin -19.700)` | `cut_extent` (§2.6) |
| `^EST(?:IMATED)? RUNTIME:\s*(.*)` | `(EST RUNTIME: 62.0 min [cut 88.25 m, rapid 8.60 m])` | `est_runtime` (§2.7) |
| anywhere: `^\s*Rapids:\s*(NUM)\s*/\s*min` | `(Rapids: 5000.0/min)` | `rapid_rate` (mm/min), used for time estimates |

Other keys (`WHEN TO RUN:`, `DEVIATIONS …:`, `ROUTER POWER …:`, `FINISH:`) are ignored.

### 2.1 Continuations

Indented comments after a key are appended (space-joined) to that key's value only for `ZERO` (and only lines that contain an axis assignment `[XYZ]0 … =`, e.g. FreeCAD's `(           Z0    = TOP FACE OF THE STOCK…)`), `CLEARANCE`, `UNITS`, `CUT EXTENT`, `EST RUNTIME` and `FLOOR`. `STOCK` takes no continuations (the post uses them for prose about the part). Tool and operation lists use continuations as described below.

### 2.2 Stock

On the `STOCK` value:
1. `(NUM)\s*x\s*(NUM)\s*x\s*(NUM)(?:\s*(mm|in)\b)?` → `size: [x, y, z]` (inches × 25.4).
2. Otherwise `(NUM)\s*x\s*(NUM)` plus `raw\s+(NUM)\s*-\s*(NUM)\s*thick` → `size: [x, y, max]`, `thickness_range: [min, max]`. (`hard maple blank 298 x 184, raw 18.3-20.5 thick`.)
3. Otherwise there is no `stock` (the sidecar requires `size`).
- `minimum: true` if `at least`, `>=` or `≥` precedes the numbers.
- `material`: if a unit word followed the numbers, the text after it up to the first `.`, `,`, `;`, `[` or `(` (`hard maple`); otherwise the text before the numbers, minus `at least` / `>=` (`MDF piece`, `acrylic sheet`).
- `text`: the whole value.
- `through[- ]cut\s+Z\s*(NUM)` anywhere in the value → `spoilboard_limit_z` (the deepest intended cut).
- `origin: "front-left-top"` when §2.3 finds an X/Y description containing `front[- ]left` and a stock word (`stock|blank|piece|sheet|material|offcut`), and a Z description containing `top` and a stock word. Analyzers only check cuts against the stock outline/thickness when `origin` is `front-left-top`.

### 2.3 Zero

On `<key>: <value>` (key text included, so `[with T02 in the collet]` counts):
- `\bwith\s+(T\d+)\b` → `zero.tool`.
- Each match of `((?:[XYZ]0\s*)+)=\s*` assigns a description to each axis letter in the group. The description runs to the next assignment, is cut at the first `. ` / `;` / ` - `, and trimmed of ` ,.;`. Example: `X0 Y0 = FRONT-LEFT corner of the STOCK, Z0 = TOP of the RAW stock. BitSetter…` → `x`, `y` = `FRONT-LEFT corner of the STOCK`, `z` = `TOP of the RAW stock`.
- `text`: the whole thing.

### 2.4 Tools

Item lines inside a `TOOLS…:` list (indented):
- `^\s*\d+\.\s+(T\d+)\b\s*(.*)` — Fabex style: `(  1. T03  2-flute upcut spiral 1/4 in   LOADS AT ~min 1)`;
- `^\s*(T\d+)\s+(NUM)\s*mm\b(.*)` — FreeCAD style: `(   T3    6.350 mm (0.2500 in)   feed  2032 mm/min   18000 RPM)`.

Other indented lines continue the current item. A `TOOL…: T…` key line is one item by itself (its indented followers continue it).

Fields, searched in the item line and its continuations joined together:

| Field | Rule |
|---|---|
| `id` | the `T\d+`, normalized to `T` + at least two digits: `T2` → `T02`, `T21` → `T21` |
| `name` | item text after the id, up to the first run of 3+ spaces, double spaces collapsed. If the first continuation's first 3-space segment doesn't mention `label`, `box`, `dia`, `feed`, `ROUTER` or `flute`, it is appended after `, ` (the brand line in `step01`). Omitted if it is just a size (`6.350 mm …`). |
| `diameter` (mm) | `\bdia\.?\s*(NUM)\s*mm` ; else FreeCAD row `T\d+\s+(NUM)\s*mm` ; else in the name segment (with `shank <size>` removed) a fraction or number followed by `in`/`i` (`1/4 in`, `1/4 i`, `1 in`; × 25.4) ; else `(NUM)\s*mm` in the name segment |
| `feed`, `plunge` | `\bfeed\s+(NUM)(?:\s*/\s*plunge\s+(NUM))?`, else `\bplunge\s+(NUM)` |
| `rpm` | `\bRPM\s+(NUM)` or `(NUM)\s*RPM\b` |
| `router_dial` | `\bROUTER DIAL\s+(NUM)` |
| `flute_length` | `\bflute\s+(NUM)\s*mm` (not `2-flute`) |
| `doc` | `\bDOC\s+(NUM)` (additive field) |
| `type` | from the name, first match wins: `ball` → `ball`; `v-?\s?(bit|groove|carve)`, `deg`, `°`, `engrav` → `vbit`; `chamfer` → `chamfer`; `spiral|upcut|downcut|flat|end ?mill|o-flute|straight|compression|surfacing` → `flat`; else `other` |

A tool listed twice (same id, e.g. T03 loaded again later) is merged: the first occurrence wins, later ones only fill missing fields. Tools without a diameter are left out of the sidecar-shaped `tools` (the schema requires one) but their id still appears in tool changes and operations.

### 2.5 Operation list

Item lines inside `OPERATIONS…:`: `^\s*(\d+)\.\s*\[(T\d+)\]\s*(?:~\s*min\s*(NUM)\s+)?(.*?)\s*$`
- number, tool, optional estimated start minute;
- the rest is `name - description` when it matches `^(\S+)\s+-\s+(.*)$` (`face_pad - Resurface the pad …`), otherwise it is only a description (`registration pocket + dogbones + finger scallops`).

The list gives names and tools to OP markers that lack them (§4). A count different from the markers is a warning.

### 2.6 Cut extent

`X\s*(NUM)\s*\.\.\s*(NUM)\s+Y\s*(NUM)\s*\.\.\s*(NUM)` optionally followed by `Z a..b`, `Zmin NUM`, `Zmax NUM` → `cut_extent: {x: [a,b], y: [a,b], z_min?, z_max?, z?, includes_cutter}`; `includes_cutter` is true when the key says `incl… cutter`. Work coordinates, mm. These are additive fields of the sidecar shape.

### 2.7 Runtime

`(NUM)\s*min` → `est_runtime.minutes`; also `\bcut\s+(NUM)\s*m\b` → `cut_m`, `\brapid\s+(NUM)\s*m\b` → `rapid_m`, `tool changes? count\s+(\d+)\s*s` → `tool_change_s` (seconds per tool change included in `minutes`).

## 3. Tool changes (from the G-code, not comments)

A tool change is any line with `M6` (`T2 M6`, `M6 T3`, `M06`). The tool is the `T` word on that line, else the last `T` word before it. **`M6` without a `T` word on the line or since the previous `M6` is flagged.** With the bridge's tool-change interceptor active (SPEC §11.5), `M6` is supported: the interceptor parks, the operator swaps the bit and the BitSetter measures it.

## 4. Operations

Found in this order; the first that yields operations wins (`operations_source`):

1. **OP markers** (`headers`), anywhere in the file: `^\s*-{2,}\s*OP\s+(\d+)\s*([:|])\s*(.*?)\s*-{2,}\s*$`
   - `|` form: `(---- OP 01 | T21 | clear_hi | Clear what T02 could not reach … ----)` → fields split on `|`: tool, name, description.
   - `:` form: `(---- OP 1: registration pocket + dogbones ----)` → description only.
   - **Range:** from the marker line to the line before the next marker, or before the first `M6` line that comes after this operation's first motion line, whichever is first (last operation: end of file); then the end moves back over blank and comment-only lines. So the tool-change block between two operations belongs to neither. Example: `step02_finish_T21.nc` OP 01 = lines `[47, 2083]`.
   - **Tool:** the marker's tool, else the operation list's tool for the same number, else the tool loaded (last `M6`) at the operation's first motion line. If the marker's tool differs from the loaded tool, analyzers warn.
   - **Name:** marker name, else the list's name, else the description, else `opNN`. Names can repeat (`screw through-bore` ×6); `number` is unique.
2. **FreeCAD comments** (`freecad-comments`): `(Begin operation: NAME)` starts an operation that runs to the next one; operations without motion (`TC_…`, `Fixture`) are dropped. Tool from the G-code.
3. **Tool changes** (`tool-changes`): one operation per `M6` segment, named after the tool (`T03`); motion before the first `M6` forms its own segment.
4. **Whole file** (`whole-file`) when there is no `M6`.

Per operation, analyzers add `z_range` (min/max Z of cutting moves) and `plunge_feed` (most common feed of pure −Z feed moves) when the source didn't give them.

## 5. Sidecar precedence

The sidecar is `<stem>.job.json` next to the file (`part.nc` → `part.job.json`); `part.nc.job.json` is also accepted. When present, its top-level fields replace the header-derived ones; header fields it doesn't set are kept (e.g. `clearance_z_work`). Its `operations`, when present, replace the header operations entirely.

## 6. Output (sidecar shape plus additive fields)

```json
{"format": "ugs-bridge-job/1", "units": "mm", "title": "…",
 "stock": {"size": [240, 95, 19.4], "material": "hard maple", "minimum": true, "origin": "front-left-top", "text": "…"},
 "zero": {"x": "FRONT-LEFT corner of the STOCK", "y": "…", "z": "TOP of the RAW stock", "tool": "T02", "text": "…"},
 "clearance_z_work": 5, "tool_change_park_z_machine": -5, "spoilboard_limit_z": -19.7,
 "tools": [{"id": "T02", "name": "2-flute downcut spiral 1/4 in, Whiteside RD2100", "type": "flat", "diameter": 6.35,
            "feed": 1778, "plunge": 508, "rpm": 18000, "router_dial": 3.0, "flute_length": 25.4, "doc": 2.54}],
 "operations": [{"number": 1, "name": "face_pad", "tool": "T02", "lines": [53, 455], "description": "…",
                 "z_range": [-7.4, 5], "plunge_feed": 508}],
 "operations_source": "headers",
 "cut_extent": {"x": [7.6, 232.4], "y": [7.6, 87.4], "z_min": -19.7, "includes_cutter": true},
 "est_runtime": {"minutes": 62.0, "cut_m": 88.25, "rapid_m": 8.6},
 "rapid_rate": 5000}
```

## 7. Test corpus expectations

`dr_ryan_complete_T02_then_T21.nc`: 9 operations (4 × T02, then 5 × T21), tool changes T02 at line 48 and T21 at line 17646 (between OP 04 and OP 05, outside both), cut extent X 7.6..232.4 Y 7.6..87.4 Zmin −19.7, about 62 min, clearance Z+5, stock 240 × 95 × 19.4. `chelsea_nameplate.nc` (FreeCAD): tools T03/T04 from `TOOLS IN RUN ORDER`, stock from `STOCK`, `FLOOR` → spoilboard limit, operations from FreeCAD comments, no clearance.

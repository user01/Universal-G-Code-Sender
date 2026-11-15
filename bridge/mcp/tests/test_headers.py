from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from mcp_paths import BRIDGE, TESTDATA, needs_testdata

from ugs_mcp.gcode.analyze import analyze_path, analyze_text
from ugs_mcp.gcode.headers import parse_headers, parse_stock, parse_tool, parse_zero
from ugs_mcp.gcode.lexer import lex

PROFILE = json.loads((BRIDGE / "profiles" / "shapeoko4-standard.json").read_text())

SYNTHETIC = """\
(FILE: demo.nc)
(STOCK: at least 100 x 50 x 10 mm walnut. Z0 = RAW top, through cut Z-10.3)
(ZERO [with T02 in the collet]: X0 Y0 = FRONT-LEFT corner of the STOCK, Z0 = TOP of the stock.)
(UNITS: mm [G21]  ABSOLUTE [G90]  work offset G54)
(CLEARANCE: every rapid/retract at Z+5 [work]. Tool-change park is G53 machine Z-5.)
(TOOLS IN THIS FILE, IN ORDER [stage these before starting]:)
(  1. T02  2-flute downcut spiral 1/4 in   LOADS AT ~min 1)
(       Whiteside RD2100   green label   feed 1778 / plunge 508 mm/min   CSV RPM 18000)
(       ROUTER DIAL 3.0  [~18000 RPM])
(  2. T21  2-flute upcut spiral 1/8 in  Whiteside RU1600   LOADS AT ~min 2)
(       dia 3.175 mm  flute 12.7 mm   feed 1143 / plunge 381 mm/min)
(OPERATIONS [with estimated start times]:)
(   1. [T02] ~min  1.0  rough - Rough it)
(   2. [T21] ~min  2.0  finish - Finish it)
(CUT EXTENT incl cutter: X -3.2..103.2  Y -3.2..51.6  Zmin -10.3)
(EST RUNTIME: 1.0 min - cut 0.3 m, rapid 0.0 m)
T2
G21
(Rapids: 4000.0/min)
G17 G90
G53 G0 Z-5
T2 M6
S18000 M3
(---- OP 01 | T02 | rough | Rough it ----)
G0 X0 Y0 Z5
G1 Z-10.3 F508
G1 X100 F1778
G0 Z5

G53 G0 Z-5
M5
(TOOL CHANGE)
T21 M6
M3
(---- OP 02 | T21 | finish | Finish it ----)
G0 X0 Y50 Z5
G1 Z-2 F381
G1 X100 F1143
G0 Z5
M5
M2
"""


def test_synthetic_header_grammar(validate: Callable[[str, Any], None]) -> None:
    r = analyze_text(SYNTHETIC, name="demo.nc", profile=PROFILE)
    job = r["job"]
    validate("job-sidecar", job)
    assert r["job_source"] == "headers"
    assert job["stock"]["size"] == [100, 50, 10]
    assert job["stock"]["material"] == "walnut"
    assert job["stock"]["minimum"] is True
    assert job["stock"]["origin"] == "front-left-top"
    assert job["zero"]["tool"] == "T02"
    assert job["zero"]["z"] == "TOP of the stock"
    assert job["clearance_z_work"] == 5
    assert job["tool_change_park_z_machine"] == -5
    assert job["spoilboard_limit_z"] == -10.3
    assert job["rapid_rate"] == 4000
    t02, t21 = job["tools"]
    assert t02["id"] == "T02"
    assert t02["diameter"] == pytest.approx(6.35)
    assert t02["name"].endswith("Whiteside RD2100")
    assert (t02["feed"], t02["plunge"], t02["rpm"], t02["router_dial"]) == (1778, 508, 18000, 3)
    assert t21["diameter"] == pytest.approx(3.175)
    assert t21["flute_length"] == 12.7
    ops = job["operations"]
    assert [(o["name"], o["tool"], o["lines"]) for o in ops] == [
        ("rough", "T02", [24, 31]),
        ("finish", "T21", [35, 41]),
    ]
    assert ops[0]["plunge_feed"] == 508
    assert ops[0]["z_range"] == [-10.3, 5]
    assert job["cut_extent"] == {
        "x": [-3.2, 103.2],
        "y": [-3.2, 51.6],
        "includes_cutter": True,
        "z_min": -10.3,
    }
    assert r["extents"]["cut_incl_cutter"]["x"] == pytest.approx([-3.175, 103.175])
    codes = {w["code"] for w in r["warnings"]}
    assert "header-extent" not in codes
    assert "into-spoilboard" in codes  # 0.3 mm below a 10 mm stock, allowed by the header


def test_header_only_tool_line_and_variants() -> None:
    tool = parse_tool(
        "T21",
        [
            "T21 2-flute upcut spiral 1/8 in, Whiteside RU1600   feed 1143 / plunge 381 mm/min"
            "   CSV RPM 18000   ROUTER DIAL 3.0   CSV DOC 1.27  flute 12.7 mm"
        ],
    )
    assert tool["name"] == "2-flute upcut spiral 1/8 in, Whiteside RU1600"
    assert tool["diameter"] == pytest.approx(3.175)
    assert tool["doc"] == 1.27
    row = parse_tool("T03", ["T3    6.350 mm (0.2500 in)   feed  2032 mm/min   18000 RPM"])
    assert (row["diameter"], row["feed"], row["rpm"]) == (6.35, 2032, 18000)
    assert parse_tool("T08", ["T08  60 deg V-groove 1/4 in max   Whiteside 1541"])["type"] == "vbit"
    assert parse_tool("T22", ["T22  tapered ball nose 1/16 in"])["type"] == "ball"
    stock = parse_stock("hard maple blank 298 x 184, raw 18.3-20.5 thick, in wood jig")
    assert stock is not None
    assert stock["size"] == [298, 184, 20.5]
    assert stock["thickness_range"] == [18.3, 20.5]
    assert parse_stock("the installed jig itself [MDF]") is None
    zero = parse_zero("WORK ZERO: X0 Y0 = front-left corner of the stock Z0 = TOP FACE, not this")
    assert zero["x"] == "front-left corner of the stock"
    assert zero["z"] == "TOP FACE, not this"


def test_no_headers_degrades_to_tool_changes() -> None:
    text = "G21\nM6 T3\nM3\nG0 X0 Y0 Z5\nG1 Z-1 F100\nG0 Z5\nM6 T4\nG1 Z-2 F100\nM2\n"
    h = parse_headers(lex(text))
    assert not h.found
    r = analyze_text(text)
    assert r["job_source"] == "gcode"
    assert [(o["name"], o["tool"], o["lines"]) for o in r["operations"]] == [
        ("T03", "T03", [2, 6]),
        ("T04", "T04", [7, 9]),
    ]


ALL_FILES = sorted(TESTDATA.glob("*.nc")) if TESTDATA.is_dir() else []


@needs_testdata
@pytest.mark.parametrize("path", ALL_FILES, ids=[p.name for p in ALL_FILES])
def test_every_testdata_file(path: Path, validate: Callable[[str, Any], None]) -> None:
    r = analyze_path(path, profile=PROFILE)
    validate("job-sidecar", r["job"])
    assert r["operations"], "every file has at least one operation"
    lines = r["file"]["lines"]
    prev_end = 0
    for op in r["operations"]:
        a, b = op["lines"]
        assert 1 <= a <= b <= lines
        assert a > prev_end, "operations don't overlap"
        prev_end = b
        assert op["tool"], f"operation {op['name']} has a tool"
    assert not r["unsupported"]
    assert r["tool_changes"], "all test files use M6"
    assert all(tc["has_t"] for tc in r["tool_changes"])
    assert not [w for w in r["warnings"] if w["level"] == "error"]
    if r["job"].get("est_runtime"):
        header = r["job"]["est_runtime"]["minutes"]
        assert r["time"]["total_min"] == pytest.approx(header, rel=0.25, abs=1.0)


@needs_testdata
def test_dr_ryan_complete() -> None:
    path = TESTDATA / "dr_ryan_complete_T02_then_T21.nc"
    if not path.is_file():
        pytest.skip("dr_ryan_complete_T02_then_T21.nc missing")
    r = analyze_path(path, profile=PROFILE)
    job = r["job"]
    assert r["file"]["lines"] == 26781
    ops = r["operations"]
    assert len(ops) == 9
    assert [o["tool"] for o in ops] == ["T02"] * 4 + ["T21"] * 5
    assert [o["name"] for o in ops] == [
        "face_pad",
        "rough_hi",
        "rough_lo_a",
        "rough_lo_b",
        "clear_hi",
        "finish_top",
        "clear_lo",
        "finish_walls_a",
        "finish_walls_b",
    ]
    assert ops[0]["lines"][0] == 53
    assert ops[4]["lines"][0] == 17651
    assert ops[3]["lines"][1] < 17646 < ops[4]["lines"][0]  # the T21 M6 sits between ops 4 and 5
    assert [tc["tool"] for tc in r["tool_changes"]] == ["T02", "T21"]
    assert r["tool_changes"][1]["line"] == 17646
    assert [t["id"] for t in job["tools"]] == ["T02", "T21"]
    assert job["tools"][0]["diameter"] == pytest.approx(6.35)
    assert job["tools"][1]["diameter"] == pytest.approx(3.175)
    assert job["stock"]["size"] == [240, 95, 19.4]
    assert job["clearance_z_work"] == 5
    assert job["cut_extent"]["x"] == [7.6, 232.4]
    assert job["cut_extent"]["y"] == [7.6, 87.4]
    assert job["cut_extent"]["z_min"] == -19.7
    ext = r["extents"]["cut_incl_cutter"]
    assert ext["x"] == pytest.approx([7.6, 232.4], abs=0.05)
    assert ext["y"] == pytest.approx([7.6, 87.4], abs=0.05)
    assert r["extents"]["cut"]["z"][0] == pytest.approx(-19.7)
    assert job["est_runtime"]["minutes"] == 62.0
    assert r["time"]["total_min"] == pytest.approx(62, abs=3)
    assert ops[-1]["z_range"][0] == pytest.approx(-19.7)
    codes = {w["code"] for w in r["warnings"]}
    assert "header-extent" not in codes
    assert "into-spoilboard" in codes


@needs_testdata
def test_chelsea_other_post_degrades_gracefully(validate: Callable[[str, Any], None]) -> None:
    path = TESTDATA / "chelsea_nameplate.nc"
    if not path.is_file():
        pytest.skip("chelsea_nameplate.nc missing")
    r = analyze_path(path, profile=PROFILE)
    validate("job-sidecar", r["job"])
    assert r["job"]["operations_source"] == "freecad-comments"
    assert [t["id"] for t in r["job"]["tools"]] == ["T03", "T04"]
    assert r["job"]["stock"]["size"] == [298.45, 171.45, 13.25]
    assert "clearance_z_work" not in r["job"]
    assert [tc["tool"] for tc in r["tool_changes"]] == ["T03", "T04"]
    assert any(w["code"] == "nested-comment" for w in r["warnings"])


def test_sidecar_wins(tmp_path: Path, validate: Callable[[str, Any], None]) -> None:
    nc = tmp_path / "part.nc"
    nc.write_text(SYNTHETIC)
    sidecar = {
        "format": "ugs-bridge-job/1",
        "units": "mm",
        "stock": {"size": [100, 50, 12], "material": "oak", "origin": "front-left-top"},
        "tools": [{"id": "T02", "type": "flat", "diameter": 6.35}],
        "operations": [{"name": "everything", "tool": "T02", "lines": [24, 41]}],
    }
    validate("job-sidecar", sidecar)
    (tmp_path / "part.job.json").write_text(json.dumps(sidecar))
    r = analyze_path(nc, profile=PROFILE)
    assert r["job_source"] == "sidecar"
    assert r["file"]["sidecar"].endswith("part.job.json")
    assert r["job"]["stock"]["material"] == "oak"
    assert [o["name"] for o in r["operations"]] == ["everything"]
    assert r["job"]["clearance_z_work"] == 5  # header fields the sidecar doesn't set remain


def test_bad_sidecar_is_reported(tmp_path: Path) -> None:
    nc = tmp_path / "part.nc"
    nc.write_text(SYNTHETIC)
    (tmp_path / "part.nc.job.json").write_text('{"format": "nope"}')
    r = analyze_path(nc)
    assert any(w["code"] == "sidecar" for w in r["warnings"])

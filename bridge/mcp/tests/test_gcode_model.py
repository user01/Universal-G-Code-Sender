from __future__ import annotations

import math

import pytest

from ugs_mcp.gcode.analyze import analyze_text
from ugs_mcp.gcode.interpreter import interpret_text
from ugs_mcp.gcode.lexer import lex_line, split_comments

PROFILE = {
    "envelope_mm": {"min": [-445, -456, -95], "max": [0, 0, 0], "verified": False},
}


def test_lexer_words_comments_and_nesting() -> None:
    line = lex_line(3, "G01 x1.5 Y-.25 (cut (deep)) F300 ; tail")
    assert line.tokens == ["G01", "X1.5", "Y-.25", "F300"]
    assert line.words[1] == ("X", 1.5)
    assert line.comments == ["cut (deep)", "tail"]
    assert line.nested_comment
    assert lex_line(1, "$H").system == "$H"
    assert lex_line(1, "G1 X1 @#").problems
    assert split_comments("(only)") == ("", ["only"], False)


def test_modal_moves_units_and_incremental() -> None:
    prog = interpret_text("G21 G90\nG0 X10 Y0 Z5\nG1 Z-1 F100\nG91 X5\nG90\nG20 G1 X1\n")
    ends = [m.end for m in prog.moves]
    assert ends[0] == (10, 0, 5)
    assert ends[1] == (10, 0, -1)
    assert ends[2] == (15, 0, -1)
    assert ends[3][0] == pytest.approx(25.4)
    assert prog.moves[1].is_plunge
    assert prog.moves[1].feed == 100


def test_arc_ijk_quadrant_extents() -> None:
    # Quarter circle CCW from (10,0) to (0,10) around the origin: crosses no axis point
    # except its ends; then a half circle CW from (0,10) to (0,-10) passes X=10.
    prog = interpret_text("G0 X10 Y0 Z0\nG3 X0 Y10 I-10 J0 F100\nG2 X0 Y-10 I0 J-10\n")
    q, h = prog.moves[1], prog.moves[2]
    assert q.lo[:2] == pytest.approx((0, 0))
    assert q.hi[:2] == pytest.approx((10, 10))
    assert q.length == pytest.approx(math.pi * 10 / 2)
    assert h.hi[0] == pytest.approx(10)
    assert h.lo[1] == pytest.approx(-10)
    assert h.length == pytest.approx(math.pi * 10)


def test_arc_full_circle_and_r_format() -> None:
    prog = interpret_text("G0 X0 Y0 Z0\nG2 X0 Y0 I5 J0 F100\nG3 X10 Y0 R5\nG3 X0 Y0 R-5\n")
    full = prog.moves[1]
    assert full.lo[:2] == pytest.approx((0, -5))
    assert full.hi[:2] == pytest.approx((10, 5))
    semi = prog.moves[2]  # CCW from (0,0) to (10,0) with R5: center (5,0), passes Y=-5
    assert semi.lo[1] == pytest.approx(-5)
    assert semi.hi[1] == pytest.approx(0)
    big = prog.moves[3]  # CCW (10,0)->(0,0), R-5 picks the >180 arc; here exactly 180
    assert big.hi[1] == pytest.approx(5)


def test_helical_arc_and_g18_plane() -> None:
    prog = interpret_text("G0 X0 Y0 Z0\nG2 X0 Y0 Z-2 I5 F100\nG18 G0 X0 Z0\nG2 X10 Z0 I5 K0\n")
    helix = prog.moves[1]
    assert helix.lo[2] == pytest.approx(-2)
    assert helix.length == pytest.approx(math.hypot(2 * math.pi * 5, 2))
    xz = prog.moves[3]
    assert xz.lo[0] == pytest.approx(0)
    assert xz.hi[0] == pytest.approx(10)
    assert abs(xz.hi[2] - xz.lo[2]) == pytest.approx(5)


def test_bad_arc_reported() -> None:
    prog = interpret_text("G0 X0 Y0\nG2 X10 Y0 I1 J0 F100\n")
    assert any(i.code == "arc" and i.level == "error" for i in prog.issues)


def test_g53_and_unknown_positions() -> None:
    prog = interpret_text("G0 X1 Y1 Z1\nG53 G0 Z-5\nG0 X2\n")
    assert prog.machine_moves[0].target == (None, None, -5)
    assert prog.moves[-1].end == (2, 1, None)


def test_tool_changes_and_m6_without_t() -> None:
    prog = interpret_text("T2\nT2 M6\nG0 Z5\nM6\nM6 T21\n")
    assert [(tc.line, tc.tool, tc.has_t) for tc in prog.tool_changes] == [
        (2, 2, True),
        (4, 2, False),
        (5, 21, True),
    ]
    assert [i.line for i in prog.issues if i.code == "M6-no-T"] == [4]
    assert "M6" not in prog.unsupported


def test_unsupported_commands() -> None:
    prog = interpret_text("G0 X0 Y0\nG41 D1\nG81 X1 Y1 Z-1 R1\nM8\nG1 A10 F100\nM7\n")
    assert {"G41", "D", "G81", "A"} <= set(prog.unsupported)
    assert "M8" not in prog.unsupported


def test_analyze_text_without_headers() -> None:
    text = "G21 G90\nM6 T1\nM3 S10000\nG0 Z5\nG0 X0 Y0\nG1 Z-2 F200\nG1 X100 F1000\nG0 Z5\nM5\nM2\n"
    r = analyze_text(text, profile=PROFILE)
    assert r["job_source"] == "gcode"
    assert r["extents"]["cut"]["x"] == [0, 100]
    assert r["extents"]["cut"]["z"] == [-2, 5]
    assert r["operations"][0]["tool"] == "T01"
    assert r["feeds"]["plunge"] == [200]
    # 7 mm plunge at 200 mm/min + 100 mm at 1000 mm/min
    assert r["time"]["cut_min"] == pytest.approx((7 / 200 + 100 / 1000), abs=0.05)
    assert not [w for w in r["warnings"] if w["level"] == "error"]


def test_analyze_flags_g53_outside_envelope_and_live_offset() -> None:
    text = "G21 G90\nG53 G0 Z5\nM3\nG0 X0 Y0 Z5\nG1 Z-1 F100\nG1 X50\nM5\nM2\n"
    r = analyze_text(text, profile=PROFILE, wco=(-10.0, -100.0, -50.0))
    codes = {w["code"] for w in r["warnings"]}
    assert "G53-outside-envelope" in codes
    assert "outside-envelope" in codes  # X 0..50 + -10 -> 40 > 0


def test_analyze_spindle_off_and_rapid_into_stock() -> None:
    text = "G0 X0 Y0 Z5\nG0 Z-1\nG1 X10 F100\nM2\n"
    codes = {w["code"] for w in analyze_text(text)["warnings"]}
    assert {"spindle-off", "rapid-below-zero", "no-profile"} <= codes

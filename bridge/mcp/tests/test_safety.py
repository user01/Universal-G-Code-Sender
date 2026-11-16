from __future__ import annotations

import json
from dataclasses import replace

import pytest

from ugs_mcp.config import local_profile
from ugs_mcp.safety import (
    Check,
    Level,
    Limits,
    LineClass,
    Machine,
    Modal,
    check_lines,
    check_motion,
    classify_line,
    classify_realtime,
    level_allows,
    stream_classification,
)


@pytest.mark.parametrize("line", ["?", "$$", "$#", "$G", "$I", "$N", " $g ", "$$ (settings)"])
def test_query_lines(line: str) -> None:
    c = classify_line(line)
    assert c.cls is LineClass.QUERY
    assert c.level is Level.QUERY


@pytest.mark.parametrize("line", ["$H", "$X", "$h"])
def test_confirm_lines(line: str) -> None:
    c = classify_line(line)
    assert c.cls is LineClass.CONFIRM
    assert c.level is Level.MOTION
    assert c.operator_dialog


@pytest.mark.parametrize(
    "line",
    ["G0 X1", "G91 G0 Z5", "$J=G91 X1 F100", "G10 L2 P1 Z0", "M3", "$C", "G38.2 Z-5 F50", "$SLP"],
)
def test_motion_lines(line: str) -> None:
    c = classify_line(line)
    assert c.cls is LineClass.MOTION
    assert c.level is Level.MOTION
    assert not c.refused_by_default


@pytest.mark.parametrize("line", ["$130=445", "$N0=G54", "$RST=*", "$RST=$"])
def test_setting_writes_refused(line: str) -> None:
    c = classify_line(line)
    assert c.cls is LineClass.MOTION
    assert c.refused_by_default


def test_realtime_and_stream() -> None:
    for op in ("hold", "resume", "jog_cancel", "reset", "status"):
        assert classify_realtime(op).cls is LineClass.REALTIME
    with pytest.raises(ValueError, match="unknown"):
        classify_realtime("jog")
    s = stream_classification()
    assert s.level is Level.STREAM
    assert s.operator_dialog


def test_levels_and_check_lines() -> None:
    assert level_allows("stream", "motion")
    assert level_allows(Level.QUERY, "query")
    assert not level_allows("query", "motion")
    assert check_lines(["$$", "?"], "query") == []
    problems = check_lines(["$$", "G0 X1", "$130=1"], "query")
    assert len(problems) == 2
    assert "motion" in problems[0]
    assert "refused" in problems[1]
    assert check_lines(["$130=1"], "stream")  # refused at every level


# --- Motion checker (SPEC §5.2) ------------------------------------------------------------

PROFILE = local_profile()[0]
LIMITS = Limits.from_profile(PROFILE)
HOMED = Machine(mpos=(-300.0, -370.0, -35.0), wco=(-280.0, -360.0, -55.6))
"""Over the BitZero, 35 mm down; G54 Z0 = stock top (-55.6)."""


def _check(
    lines: list[str],
    machine: Machine = HOMED,
    limits: Limits = LIMITS,
    modal: Modal | None = None,
    expect: str | None = "test",
    default_modal: bool = True,
) -> Check:
    start = modal if modal is not None else (Modal() if default_modal else None)
    return check_motion(lines, machine, limits, expect=expect, start_modal=start)


ALLOWED: list[tuple[str, list[str]]] = [
    ("probe Z with retract", ["G21 G91 G38.2 Z-25 F100", "G0 Z2", "G90"]),
    ("retract then machine rapid", ["G53 G0 Z-5", "G53 G0 X-10 Y-10"]),
    ("work rapid Z only", ["G0 Z5"]),
    ("slow XY move low", ["G1 X-10 F500"]),
    ("relative restored", ["G91 G0 Z5", "G90"]),
    ("inches restored", ["G20 G0 Z0.1", "G21"]),
    ("inverse time restored", ["G93 G1 Z-1 F10", "G94"]),
    ("arc ijk", ["G53 G0 Z-5", "G2 X10 Y0 I5 J0 F500"]),
    ("jog relative", ["$J=G91 X5 F1000"]),
    ("jog absolute machine", ["$J=G53 X-200 F1000"]),
    ("jog in inches is local", ["$J=G20 G91 Z0.5 F20"]),
    ("set offset L2", ["G10 L2 P1 Z-50"]),
    ("set offset L20", ["G10 L20 P1 Z13"]),
    ("queries", ["$G", "$#", "?"]),
    ("dwell and M5", ["G4 P0.5", "M5"]),
    ("spindle off", ["M5"]),
    ("spindle off with S0", ["M5 S0"]),
    ("retract then spindle off", ["G53 G0 Z-5", "M05"]),
    ("comments", ["(retract) G53 G0 Z-5 ; done"]),
    ("probe then absolute move", ["G91 G38.2 Z-10 F100", "G90 G53 G0 Z-5"]),
    ("home alone", ["$H"]),
]


@pytest.mark.parametrize(("name", "lines"), ALLOWED, ids=[a for a, _ in ALLOWED])
def test_checker_allows(name: str, lines: list[str]) -> None:
    c = _check(lines)
    assert c.ok, c.message()


REFUSED: list[tuple[str, list[str], str]] = [
    ("M6", ["M6 T2"], "tool_change"),
    ("T then M6", ["T2", "M6"], "tool_change"),
    ("spindle M3", ["M3 S10000"], "spindle"),
    ("spindle M4", ["M4"], "spindle"),
    ("spindle M03", ["M03"], "spindle"),
    ("spindle M3 after M5", ["M5", "M3"], "spindle"),
    ("spindle speed alone", ["S12000"], "spindle"),
    ("spindle speed with a move", ["G1 X-10 F500 S1000"], "spindle"),
    ("program end", ["M30"], "program_flow"),
    ("pause", ["M0"], "program_flow"),
    ("setting write", ["$130=845"], "setting_write"),
    ("startup block", ["$N0=G54"], "setting_write"),
    ("restore settings", ["$RST=*"], "setting_write"),
    ("sleep", ["$SLP"], "system_cmd"),
    ("check mode", ["$C"], "system_cmd"),
    ("G91 leak", ["G91 G0 Z5"], "modal_restore"),
    ("G20 leak", ["G20 G0 Z0.1"], "modal_restore"),
    ("G93 leak", ["G93 G1 Z-1 F10"], "modal_restore"),
    ("plane leak", ["G18"], "modal_restore"),
    ("envelope X", ["G53 G0 Z-5", "G53 G0 X-500"], "envelope"),
    ("envelope Z up", ["G53 G0 Z5"], "envelope"),
    ("envelope Z down", ["G53 G1 Z-100 F500"], "envelope"),
    ("envelope relative", ["G91 G0 Z40", "G90"], "envelope"),
    ("envelope work coords", ["G0 Z60"], "envelope"),
    ("envelope inches", ["G20 G53 G0 Z-4", "G21"], "envelope"),
    ("envelope arc", ["G53 G0 Z-5", "G53 G0 X-440", "G91 G2 X0 Y0 I-10 F500", "G90"], "envelope"),
    ("envelope jog", ["$J=G91 Y-100 F1000"], "envelope"),
    ("rapid XY below safe Z", ["G53 G0 X-200"], "rapid_below_safe_z"),
    ("probe absolute", ["G38.2 Z-60 F100"], "probe_relative"),
    ("probe too far", ["G91 G38.2 Z-30 F100", "G90"], "probe_bounded"),
    ("probe too far inches", ["G20 G91 G38.2 Z-1.1 F4", "G21 G90"], "probe_bounded"),
    ("probe up", ["G91 G38.2 Z5 F100", "G90"], "probe_axis"),
    ("probe two axes", ["G91 G38.2 X-5 Z-5 F100", "G90"], "probe_axis"),
    ("probe XY without bore", ["G91 G38.2 X-10 F100", "G90"], "probe_axis"),
    ("probe no feed", ["G91 G38.2 Z-5", "G90"], "probe_feed"),
    ("probe too fast", ["G91 G38.2 Z-5 F2000", "G90"], "probe_feed"),
    ("two probes", ["G91 G38.2 Z-5 F200", "G0 Z1", "G38.2 Z-3 F50", "G90"], "probe_one"),
    ("modal probe repeat", ["G91 G38.2 Z-5 F100", "Z2", "G90"], "probe_one"),
    ("probe away", ["G91 G38.4 Z-5 F100", "G90"], "unsupported"),
    ("G28", ["G28"], "unsupported"),
    ("G92", ["G92 Z0"], "unsupported"),
    ("G55", ["G55"], "unsupported"),
    ("G43.1", ["G43.1 Z5"], "unsupported"),
    ("cutter comp", ["G41 D1"], "unsupported"),
    ("canned cycle", ["G81 Z-5 R1 F100"], "unsupported"),
    ("rotary axis", ["G0 A10"], "unsupported"),
    ("G10 L1", ["G10 L1 P1 Z5"], "unsupported"),
    ("G10 P2", ["G10 L2 P2 Z5"], "unsupported"),
    ("G10 origin outside", ["G10 L2 P1 X100"], "envelope"),
    ("G10 L20 after probe", ["G91 G38.2 Z-5 F100", "G90", "G10 L20 P1 Z13"], "position"),
    ("XZ arc after probe", ["G91 G38.2 Z-5 F100", "G90 G18", "G2 X-1 K1 F99", "G17"], "position"),
    ("no feed", ["G1 X-10"], "feed_limit"),
    ("feed above rapid", ["G1 X-10 F9000"], "feed_limit"),
    ("jog no feed", ["$J=G91 X5"], "feed_limit"),
    ("jog bad word", ["$J=G91 G1 X5 F100"], "syntax"),
    ("garbage", ["G0 X1..2"], "syntax"),
    ("G53 arc", ["G53 G2 X1 I1 F100"], "syntax"),
    ("home with others", ["$H", "G0 Z-5"], "confirm_alone"),
    ("empty", ["(comment)"], "empty"),
    ("too many", ["G4 P0"] * 51, "line_count"),
]


@pytest.mark.parametrize(("name", "lines", "rule"), REFUSED, ids=[r[0] for r in REFUSED])
def test_checker_refuses(name: str, lines: list[str], rule: str) -> None:
    c = _check(lines)
    assert not c.ok
    assert rule in c.rules, c.message()
    assert c.message().startswith("Refused, nothing was sent:")


def test_spindle_refusal_says_the_operator_starts_the_router() -> None:
    for lines in (["M3 S10000"], ["M4"], ["S8000"]):
        msg = _check(lines).message()
        assert "the operator starts the router" in msg, msg
        assert "M5" in msg, msg


@pytest.mark.parametrize(
    "machine",
    [
        replace(HOMED, homed=False),
        replace(HOMED, state="Alarm", alarm="ALARM:5"),
        replace(HOMED, state="Hold"),
        replace(HOMED, probe_pin=None),
    ],
    ids=["unhomed", "alarm", "hold", "probe pin unknown"],
)
def test_spindle_off_needs_no_homing_or_idle(machine: Machine) -> None:
    """M5 moves nothing: the motion-only rules (homed, Idle) don't apply; authority still does."""
    c = check_motion(["M5"], machine, LIMITS, expect="router stops")
    assert c.ok, c.message()
    assert not check_motion(["M5"], replace(machine, level="query"), LIMITS, expect="x").ok


MACHINE_REFUSALS: list[tuple[str, Machine, str]] = [
    ("unhomed", replace(HOMED, homed=False), "homed"),
    ("alarm", replace(HOMED, state="Alarm", alarm="ALARM:5"), "state"),
    ("hold", replace(HOMED, state="Hold"), "state"),
    ("running", replace(HOMED, state="Run"), "state"),
    ("query level", replace(HOMED, level="query"), "authority"),
    ("locked", replace(HOMED, locked=True), "authority"),
    ("disconnected", replace(HOMED, connected=False), "connection"),
    ("streaming", replace(HOMED, stream="running"), "busy"),
    ("interceptor", replace(HOMED, interceptor="waiting"), "busy"),
    ("no position", replace(HOMED, mpos=None), "position"),
    ("probe pin active", replace(HOMED, probe_pin=True), "probe_clear"),
    ("probe pin unknown", replace(HOMED, probe_pin=None), "probe_clear"),
]


@pytest.mark.parametrize(
    ("name", "machine", "rule"), MACHINE_REFUSALS, ids=[m[0] for m in MACHINE_REFUSALS]
)
def test_checker_machine_state(name: str, machine: Machine, rule: str) -> None:
    c = _check(["G91 G38.2 Z-10 F100", "G0 Z2", "G90"], machine=machine)
    assert rule in c.rules, c.message()


def test_unknown_wco_refuses_work_moves() -> None:
    c = _check(["G0 Z5"], machine=replace(HOMED, wco=None))
    assert "position" in c.rules
    assert _check(["G53 G0 Z-5"], machine=replace(HOMED, wco=None)).ok


def test_home_and_unlock_allowed_in_alarm_unhomed() -> None:
    m = replace(HOMED, state="Alarm", homed=False)
    for line in ("$H", "$X"):
        c = _check([line], machine=m)
        assert c.ok, c.message()
        assert c.confirm == line
    assert "authority" in _check(["$H"], machine=replace(m, level="query")).rules


def test_queries_need_no_motion_authority() -> None:
    m = replace(HOMED, level="query", state="Alarm", homed=False)
    assert _check(["$G", "$#"], machine=m).ok


def test_expect_is_required() -> None:
    assert "expect" in _check(["G53 G0 Z-5"], expect="  ").rules
    assert "expect" in _check(["G53 G0 Z-5"], expect=None).rules


def test_controller_starting_in_g91_is_tracked() -> None:
    start = Modal(distance="G91")
    c = _check(["G0 Z1"], modal=start)
    assert "modal_restore" in c.rules  # the operator left G91; the call must restore G90
    c = _check(["G0 Z1", "G90"], modal=start)
    assert c.ok, c.message()
    assert c.moves[0].hi[2] == pytest.approx(-34.0)  # read as relative: -35 + 1


def test_unread_modal_is_noted() -> None:
    c = _check(["G53 G0 Z-5"], default_modal=False)
    assert c.ok
    assert any("wasn't read" in n for n in c.notes)


def test_probe_span_is_conservative() -> None:
    # Start Z -35: contact is anywhere in [-60, -35], so a 36 mm retract could reach +1.
    c = _check(["G91 G38.2 Z-25 F100", "G0 Z36", "G90"])
    assert "envelope" in c.rules
    ok = _check(["G91 G38.2 Z-25 F100", "G0 Z30", "G90"])
    assert ok.ok, ok.message()
    assert ok.end_span is not None
    assert ok.end_span[2] == pytest.approx([-30.0, -5.0])


def test_diagonal_rapid_down_from_safe_z_is_refused() -> None:
    at_safe = replace(HOMED, mpos=(-1.0, -1.0, -5.0))
    c = _check(["G53 G0 X-300 Y-370 Z-35"], machine=at_safe)
    assert c.rules == ["rapid_below_safe_z"]
    assert _check(["G53 G0 X-300 Y-370"], machine=at_safe).ok


def test_envelope_reported_once_and_advisory_mode() -> None:
    preamble = ["G21 G53 G0 Z-5", "G21 G90 G17 G94 G54", "G0 X10 Y20", "G0 Z5", "M3", "G4 P2"]
    origin_zero = replace(HOMED, wco=(0.0, 0.0, 0.0), homed=False, state="Alarm")
    c = check_motion(preamble, origin_zero, LIMITS, expect=None, allow_spindle=True, advisory=True)
    flagged = [(r.line, r.message.split(" mm")[0][-20:]) for r in c.refusals]
    assert [r.rule for r in c.refusals] == ["envelope", "envelope", "envelope"]
    assert [ln for ln, _ in flagged] == [3, 3, 4]  # X and Y on line 3, Z on line 4 only


def test_retract_near_top_is_refused() -> None:
    near_top = replace(HOMED, mpos=(-300.0, -370.0, -1.0))
    c = _check(["G91 G38.2 Z-5 F100", "G0 Z2", "G90"], machine=near_top)
    assert "envelope" in c.rules


def test_xy_probe_allowed_with_bore() -> None:
    prof = json.loads(json.dumps(PROFILE))
    prof["bitzero"]["bore_diameter"] = 22.0
    limits = Limits.from_profile(prof)
    c = _check(["G91 G38.2 X-10 F100", "G90"], limits=limits)
    assert c.ok, c.message()
    assert any("bore_center_to_corner" in a for a in limits.assumptions)


def test_profile_without_envelope_refuses_motion() -> None:
    limits = Limits.from_profile({"safe_z_machine": -5})
    c = _check(["G53 G0 Z-5"], limits=limits)
    assert "envelope" in c.rules
    assert _check(["$G"], limits=limits).ok


def test_limits_record_unverified_assumptions() -> None:
    assert LIMITS.envelope is not None
    assert not LIMITS.envelope.verified
    assert any("ledger A1/C6" in a for a in LIMITS.assumptions)
    assert LIMITS.plate_thickness == 13.0
    assert not LIMITS.plate_verified
    assert not LIMITS.xy_probe
    assert "B3" in LIMITS.xy_probe_reason
    assert LIMITS.probe_max == 25.0


def test_parse_gc_and_from_status() -> None:
    md = Modal.parse_gc("[GC:G1 G54 G18 G20 G91 G93 M5 M9 T2 F10 S0]")
    assert md is not None
    assert (md.units, md.distance, md.feed_mode, md.plane, md.motion) == (
        "G20",
        "G91",
        "G93",
        "G18",
        "G1",
    )
    assert md.feed == pytest.approx(254.0)
    assert Modal.parse_gc("ok") is None
    m = Machine.from_status(
        {
            "connection": {"connected": True},
            "controller": {
                "state": "Idle",
                "mpos": [-1, -2, -3],
                "wco": [0, 0, -50],
                "pins": {"raw": "P", "probe": True},
            },
            "homed_this_connection": True,
            "authority": {"locked": False, "level": "motion"},
            "stream": {"state": "idle"},
            "alarm": None,
            "interceptor": None,
        }
    )
    assert m.homed
    assert m.probe_pin
    assert m.level == "motion"
    assert m.mpos == (-1.0, -2.0, -3.0)
    assert m.interceptor is None


def test_estimate_and_failure_modal() -> None:
    c = _check(["G91 G38.2 Z-10 F100", "G0 Z2", "G90"])
    assert c.estimate_s(5000) == pytest.approx(6.0, abs=0.1)  # 10 mm at 100 mm/min
    assert c.modal_after[0].distance == "G91"
    assert c.modal_before(1).distance == "G90"
    assert c.modal_before(3).distance == "G91"

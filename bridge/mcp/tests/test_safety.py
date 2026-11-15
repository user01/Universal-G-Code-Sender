from __future__ import annotations

import pytest

from ugs_mcp.safety import (
    Level,
    LineClass,
    check_lines,
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

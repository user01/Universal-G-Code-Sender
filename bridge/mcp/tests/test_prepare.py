from __future__ import annotations

import hashlib
import os
from pathlib import Path

import pytest
from conftest import TESTDATA, needs_testdata

from ugs_mcp.gcode.prepare import PrepareError, apply_edits, prepare

SRC = "G21 G90\nT2 M6\nM3\nG0 X0 Y0 Z5 (go)\nG1 Z-1 F100\nG41 D1\nM6 T3\nG1 X10\nM2\n"


def _snapshot(p: Path) -> tuple[str, int, int]:
    st = p.stat()
    return hashlib.sha256(p.read_bytes()).hexdigest(), st.st_mtime_ns, st.st_mode


@pytest.fixture
def src(tmp_path: Path) -> Path:
    p = tmp_path / "job.nc"
    p.write_text(SRC)
    os.chmod(p, 0o444)  # read-only: any write attempt would fail loudly
    return p


def test_writes_sibling_and_leaves_original(src: Path) -> None:
    before = _snapshot(src)
    r = prepare(src, [{"op": "remove_m6"}])
    assert _snapshot(src) == before
    out = Path(r["output"])
    assert out.parent == src.parent
    assert out.name == "job.ugs.nc"
    text = out.read_text()
    assert "M6" not in text
    assert "T2\n" in text
    assert "T3\n" in text
    assert "-T2 M6" in r["diff"]
    assert "+T2" in r["diff"]
    assert r["m6_remaining"] == 0
    assert r["remaining_unsupported"] == ["D", "G41"]
    assert r["sha256"] == hashlib.sha256(out.read_bytes()).hexdigest()


def test_refuses_to_overwrite_original_or_leave_folder(src: Path, tmp_path: Path) -> None:
    with pytest.raises(PrepareError, match="original"):
        prepare(src, [{"op": "remove_m6"}], output=src)
    with pytest.raises(PrepareError, match="original"):
        prepare(src, [{"op": "remove_m6"}], output=Path("job.nc"))
    link = tmp_path / "alias.nc"
    link.symlink_to(src)
    with pytest.raises(PrepareError):
        prepare(src, [{"op": "remove_m6"}], output=link, overwrite=True)
    sub = tmp_path / "sub"
    sub.mkdir()
    with pytest.raises(PrepareError, match="sibling"):
        prepare(src, [{"op": "remove_m6"}], output=sub / "x.nc")
    assert src.read_text() == SRC


def test_existing_output_needs_overwrite(src: Path) -> None:
    prepare(src, [{"op": "remove_m6"}])
    with pytest.raises(PrepareError, match="overwrite"):
        prepare(src, [{"op": "remove_m6"}])
    r = prepare(src, [{"op": "delete", "lines": [6, 6]}], overwrite=True)
    assert "G41" not in Path(r["output"]).read_text()


def test_line_edits_use_original_numbers() -> None:
    _, edited = apply_edits(
        SRC,
        [
            {"op": "insert_before", "line": 1, "text": "(prepared)\n(two)"},
            {"op": "delete", "lines": [2, 2]},
            {"op": "replace", "line": 5, "text": "G1 Z-0.5 F80"},
            {"op": "comment_out", "lines": [6, 6]},
            {"op": "insert_after", "line": 9, "text": "%"},
            {"op": "regex", "pattern": r"X10\b", "replacement": "X12", "lines": [8, 8]},
        ],
    )
    assert edited[:2] == ["(prepared)", "(two)"]
    assert "T2 M6" not in edited
    assert "G1 Z-0.5 F80" in edited
    assert ";G41 D1" in edited
    assert "G1 X12" in edited
    assert edited[-1] == "%"


def test_comment_out_unsupported() -> None:
    _, edited = apply_edits(SRC, [{"op": "comment_out_unsupported"}])
    assert ";G41 D1" in edited


@pytest.mark.parametrize(
    "edit",
    [
        {"op": "bogus"},
        {"op": "delete", "lines": [0, 3]},
        {"op": "delete"},
        {"op": "regex", "pattern": "("},
        {"nope": 1},
    ],
)
def test_bad_edits(edit: dict[str, object], src: Path) -> None:
    with pytest.raises(PrepareError):
        prepare(src, [edit])  # type: ignore[list-item]
    assert not (src.parent / "job.ugs.nc").exists()


@needs_testdata
def test_real_file_untouched(tmp_path: Path) -> None:
    real = TESTDATA / "step40_only_one_lid_T07_v0.12.nc"
    if not real.is_file():
        pytest.skip("missing")
    copy = tmp_path / real.name
    copy.write_bytes(real.read_bytes())
    before = _snapshot(copy)
    r = prepare(copy, [{"op": "remove_m6"}])
    assert _snapshot(copy) == before
    assert r["lines"] == r["original_lines"]
    assert r["m6_remaining"] == 0

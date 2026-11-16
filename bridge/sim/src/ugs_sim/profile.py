"""Machine profile (bridge/profiles/*.json) as far as the simulator needs it."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

DEFAULT_PROFILE_NAME = "shapeoko4-standard.json"


def _repo_profiles_dir() -> Path:
    # src/ugs_sim/profile.py -> parents: ugs_sim, src, sim, bridge
    return Path(__file__).resolve().parents[3] / "profiles"


@dataclass(frozen=True, slots=True)
class BitSetterProfile:
    xy: tuple[float, float] = (-4.0, -449.0)
    start_z_machine: float = -5.0
    max_travel: float = 90.0


@dataclass(frozen=True, slots=True)
class MachineProfile:
    name: str = "Shapeoko 4 Standard (built-in defaults)"
    envelope_min: tuple[float, float, float] = (-445.0, -456.0, -95.0)
    envelope_max: tuple[float, float, float] = (0.0, 0.0, 0.0)
    bitsetter: BitSetterProfile = field(default_factory=BitSetterProfile)
    bitzero_plate_thickness: float | None = None
    bitzero_outer_size: float | None = None
    bitzero_bore_diameter: float | None = None
    """Ledger B3; ``None`` until measured (the simulator then uses a marked placeholder)."""
    bitzero_bore_center_to_corner: tuple[float, float] | None = None
    connect_resets_controller: bool = True
    expected_settings: dict[int, float] = field(default_factory=dict)

    @property
    def travel(self) -> tuple[float, float, float]:
        """GRBL ``$130``-``$132``: the envelope size per axis."""
        mn, mx = self.envelope_min, self.envelope_max
        return (mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2])

    @classmethod
    def from_dict(cls, doc: dict[str, Any]) -> MachineProfile:
        env = doc.get("envelope_mm", {})
        bs = doc.get("bitsetter", {})
        bz = doc.get("bitzero", {})
        default = cls()
        settings: dict[int, float] = {}
        for key, value in doc.get("expected_settings", {}).items():
            settings[int(str(key).lstrip("$"))] = float(value)
        return cls(
            name=str(doc.get("name", default.name)),
            envelope_min=_vec3(env.get("min"), default.envelope_min),
            envelope_max=_vec3(env.get("max"), default.envelope_max),
            bitsetter=BitSetterProfile(
                xy=_vec2(bs.get("xy"), default.bitsetter.xy),
                start_z_machine=float(bs.get("start_z_machine", default.bitsetter.start_z_machine)),
                max_travel=float(bs.get("max_travel", default.bitsetter.max_travel)),
            ),
            bitzero_plate_thickness=(
                float(bz["plate_thickness"]) if bz.get("plate_thickness") is not None else None
            ),
            bitzero_outer_size=_first(bz.get("outer_size")),
            bitzero_bore_diameter=_opt_float(bz.get("bore_diameter")),
            bitzero_bore_center_to_corner=_xy_or_scalar(bz.get("bore_center_to_corner")),
            connect_resets_controller=bool(doc.get("connect_resets_controller", True)),
            expected_settings=settings,
        )


def _opt_float(value: Any) -> float | None:
    return None if value is None else float(value)


def _first(value: Any) -> float | None:
    """A square size given as a number or as ``[w, h]`` (the first is used)."""
    if value is None:
        return None
    if isinstance(value, list | tuple):
        return float(value[0])
    return float(value)


def _xy_or_scalar(value: Any) -> tuple[float, float] | None:
    if value is None:
        return None
    if isinstance(value, list | tuple):
        return _vec2(value, (0.0, 0.0))
    return (float(value), float(value))


def _vec3(value: Any, default: tuple[float, float, float]) -> tuple[float, float, float]:
    if value is None:
        return default
    x, y, z = (float(v) for v in value)
    return (x, y, z)


def _vec2(value: Any, default: tuple[float, float]) -> tuple[float, float]:
    if value is None:
        return default
    x, y = (float(v) for v in value)
    return (x, y)


def default_profile_path() -> Path | None:
    """``$UGS_SIM_PROFILE``, else the repo's Shapeoko 4 profile when running from a checkout."""
    env = os.environ.get("UGS_SIM_PROFILE")
    if env:
        return Path(env)
    candidate = _repo_profiles_dir() / DEFAULT_PROFILE_NAME
    return candidate if candidate.is_file() else None


def load_profile(path: Path | str | None = None) -> MachineProfile:
    """Load a profile file; with no path, the default one, else built-in defaults."""
    resolved = Path(path) if path is not None else default_profile_path()
    if resolved is None:
        return MachineProfile()
    with resolved.open(encoding="utf-8") as fh:
        doc = json.load(fh)
    if not isinstance(doc, dict):
        raise ValueError(f"{resolved}: profile must be a JSON object")
    return MachineProfile.from_dict(doc)

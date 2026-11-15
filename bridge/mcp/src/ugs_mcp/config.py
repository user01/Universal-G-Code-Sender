"""Locations: the bridge home (``UGS_BRIDGE_HOME``) and the machine profile."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

DEFAULT_HOME = Path("~/Library/Application Support/ugs-bridge")
REPO_PROFILE = Path(__file__).resolve().parents[3] / "profiles" / "shapeoko4-standard.json"
"""``bridge/profiles/…`` when running from the repository checkout."""


def bridge_home() -> Path:
    env = os.environ.get("UGS_BRIDGE_HOME")
    return Path(env).expanduser() if env else DEFAULT_HOME.expanduser()


def local_profile() -> tuple[dict[str, Any] | None, str | None]:
    """The profile from ``UGS_BRIDGE_PROFILE`` or the repository default, with its path."""
    env = os.environ.get("UGS_BRIDGE_PROFILE")
    for cand in ([Path(env).expanduser()] if env else []) + [REPO_PROFILE]:
        try:
            data = json.loads(cand.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(data, dict):
            return data, str(cand)
    return None, None

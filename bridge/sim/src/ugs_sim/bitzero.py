"""BitZero V2 block geometry and exact probe-contact math along straight moves.

The block sits on the stock with its registration lips hooked over one stock corner (the
front-left one by default). Everything is in machine coordinates; Z values are tool-*tip* Z.

* ``corner``: the registration corner, i.e. the stock corner the lips register against.
* The conductive aluminum body is a ``size`` x ``size`` square whose front and left outer faces
  stand ``lip_mm`` proud of the corner (the lips wrap the stock edges). Its top is
  ``thickness`` above the stock top.
* A through ``bore`` of ``bore_diameter`` whose center is ``bore_offset`` (x, y) in from the
  registration corner, towards the stock.

The tool is a vertical cylinder of ``tool_diameter`` with its flat end at the tip. It touches
the block when the tip is at or below the block top and the tool's disc overlaps the
aluminum (square minus bore). That covers Z probing on the top, XY probing against the bore
wall and XY probing against the outer faces.

**Placeholders**: ledger B3 (bore diameter, bore-center-to-corner offset) is unknown, and the
lip thickness is a guess. Only the 13 mm thickness (B1) and the 58 mm footprint (B2) are
sourced. Nothing here is measured.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

EPS = 1e-9
INF = math.inf

Vec3 = tuple[float, float, float]
Interval = tuple[float, float]

# Placeholder geometry until ledger B3 is measured (see module docstring).
PLACEHOLDER_BORE_DIAMETER = 22.0
PLACEHOLDER_BORE_OFFSET = 14.5
PLACEHOLDER_LIP_MM = 2.0
SOURCED_SIZE_MM = 58.0  # ledger B2
SOURCED_THICKNESS_MM = 13.0  # ledger B1
DEFAULT_TOOL_DIAMETER = 6.35


@dataclass
class BitZeroBlock:
    corner: tuple[float, float]
    stock_top_z: float
    thickness: float = SOURCED_THICKNESS_MM
    size: float = SOURCED_SIZE_MM
    bore_diameter: float = PLACEHOLDER_BORE_DIAMETER
    bore_offset: tuple[float, float] = (PLACEHOLDER_BORE_OFFSET, PLACEHOLDER_BORE_OFFSET)
    lip_mm: float = PLACEHOLDER_LIP_MM

    @property
    def top_z(self) -> float:
        return self.stock_top_z + self.thickness

    @property
    def square(self) -> tuple[float, float, float, float]:
        """Outer faces of the aluminum body: x_min, y_min, x_max, y_max."""
        x0 = self.corner[0] - self.lip_mm
        y0 = self.corner[1] - self.lip_mm
        return (x0, y0, x0 + self.size, y0 + self.size)

    @property
    def bore_center(self) -> tuple[float, float]:
        return (self.corner[0] + self.bore_offset[0], self.corner[1] + self.bore_offset[1])

    def describe(self) -> dict[str, object]:
        return {
            "corner": list(self.corner),
            "top_z": self.top_z,
            "thickness": self.thickness,
            "size": self.size,
            "square": list(self.square),
            "bore_diameter": self.bore_diameter,
            "bore_offset": list(self.bore_offset),
            "bore_center": list(self.bore_center),
            "lip_mm": self.lip_mm,
        }

    def contact_intervals(
        self, a: Vec3, b: Vec3, tool_length: float, tool_radius: float, slack: float = 0.0
    ) -> list[Interval]:
        """Parameter ranges ``t`` (``a + t (b - a)``, spindle coordinates) in contact.

        ``slack`` widens the contact zone: the pin check at a resting position uses a little
        more than the motion that stopped exactly on the boundary, so it reads closed there.
        """
        tool_radius += EPS + slack
        top_spindle = self.top_z + tool_length + EPS + slack
        iz = _slab(a[2], b[2] - a[2], -INF, top_spindle)
        ixy = _rounded_rect(a, b, self.square, tool_radius)
        base = _intersect(iz, ixy)
        if base is None:
            return []
        rho = self.bore_diameter / 2.0 - tool_radius
        if rho <= 0:
            return [base]  # the tool can't fit inside the bore: it always touches
        out = []
        for piece in _outside_circle(a, b, self.bore_center, rho):
            hit = _intersect(base, piece)
            if hit is not None:
                out.append(hit)
        return out


def box_intervals(
    a: Vec3,
    b: Vec3,
    rect: tuple[float, float, float, float],
    top_spindle_z: float,
    slack: float = 0.0,
) -> list[Interval]:
    """Point tool against a box: XY inside ``rect`` and spindle Z at or below ``top``."""
    top_spindle_z += slack
    iv: Interval | None = (-INF, INF)
    for lo, hi, axis in ((rect[0], rect[2], 0), (rect[1], rect[3], 1)):
        iv = _intersect(iv, _slab(a[axis], b[axis] - a[axis], lo - EPS, hi + EPS))
    iv = _intersect(iv, _slab(a[2], b[2] - a[2], -INF, top_spindle_z + EPS))
    return [] if iv is None else [iv]


def first_contact(intervals: list[Interval]) -> float | None:
    """Smallest ``t`` in [0, 1] inside any interval (probe toward the workpiece)."""
    best: float | None = None
    for lo, hi in intervals:
        start = max(lo, 0.0)
        if start <= min(hi, 1.0) and (best is None or start < best):
            best = start
    return best


def contact_lost(intervals: list[Interval]) -> float | None:
    """End of the contact range that holds at ``t = 0`` (probe away), if within [0, 1]."""
    merged: list[list[float]] = []
    for lo, hi in sorted(intervals):
        if merged and lo <= merged[-1][1] + EPS:
            merged[-1][1] = max(merged[-1][1], hi)
        else:
            merged.append([lo, hi])
    for lo, hi in merged:
        if lo <= EPS and hi >= -EPS:
            return hi if hi <= 1.0 else None
    return 0.0  # not in contact at the start


# ---------------------------------------------------------------- line clipping


def _intersect(p: Interval | None, q: Interval | None) -> Interval | None:
    if p is None or q is None:
        return None
    lo, hi = max(p[0], q[0]), min(p[1], q[1])
    return (lo, hi) if lo <= hi else None


def _slab(origin: float, delta: float, lo: float, hi: float) -> Interval | None:
    """``t`` range where ``lo <= origin + t * delta <= hi``."""
    if abs(delta) < 1e-15:
        return (-INF, INF) if lo <= origin <= hi else None
    t0, t1 = (lo - origin) / delta, (hi - origin) / delta
    return (min(t0, t1), max(t0, t1))


def _circle_inside(a: Vec3, b: Vec3, center: tuple[float, float], r: float) -> Interval | None:
    """``t`` range where the XY point is within ``r`` of ``center``."""
    ox, oy = a[0] - center[0], a[1] - center[1]
    dx, dy = b[0] - a[0], b[1] - a[1]
    qa = dx * dx + dy * dy
    qc = ox * ox + oy * oy - r * r
    if qa < 1e-24:
        return (-INF, INF) if qc <= 0 else None
    qb = 2.0 * (ox * dx + oy * dy)
    disc = qb * qb - 4.0 * qa * qc
    if disc < 0:
        return None
    root = math.sqrt(disc)
    return ((-qb - root) / (2.0 * qa), (-qb + root) / (2.0 * qa))


def _outside_circle(a: Vec3, b: Vec3, center: tuple[float, float], r: float) -> list[Interval]:
    inside = _circle_inside(a, b, center, r)
    if inside is None:
        return [(-INF, INF)]
    if inside == (-INF, INF):
        return []
    # Closed at the wall: touching the wall is contact.
    return [(-INF, inside[0]), (inside[1], INF)]


def _rounded_rect(
    a: Vec3, b: Vec3, rect: tuple[float, float, float, float], r: float
) -> Interval | None:
    """``t`` range where the XY point is within ``r`` of ``rect`` (a convex set)."""
    x0, y0, x1, y1 = rect
    dx, dy = b[0] - a[0], b[1] - a[1]
    pieces = [
        _intersect(_slab(a[0], dx, x0 - r, x1 + r), _slab(a[1], dy, y0, y1)),
        _intersect(_slab(a[0], dx, x0, x1), _slab(a[1], dy, y0 - r, y1 + r)),
    ]
    pieces += [_circle_inside(a, b, c, r) for c in ((x0, y0), (x1, y0), (x0, y1), (x1, y1))]
    found = [p for p in pieces if p is not None]
    if not found:
        return None
    return (min(p[0] for p in found), max(p[1] for p in found))

/*
    Copyright 2026 ugs-bridge contributors

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.ugs.nbp.bridge.job;

import com.willwinder.universalgcodesender.gcode.GcodeState;

/**
 * Modal state in effect at the start of a line. Immutable; {@link JobModel} interns equal instances so a 27k-line
 * file holds only a few hundred distinct objects.
 *
 * @param units      G20 or G21
 * @param distance   G90 or G91
 * @param plane      G17, G18 or G19
 * @param wcs        G54..G59.3
 * @param feedMode   G93, G94 or G95
 * @param motion     G0, G1, G2, G3, G38.2 ... or null
 * @param feed       modal F (file units per minute)
 * @param spindle    M3, M4 or M5
 * @param speed      modal S
 */
public record Modal(String units, String distance, String plane, String wcs, String feedMode, String motion,
                    double feed, String spindle, double speed) {

    public static Modal of(GcodeState s) {
        return new Modal(
                s.units == null ? "G21" : s.units.toString(),
                s.distanceMode == null ? "G90" : s.distanceMode.toString(),
                s.plane == null ? "G17" : s.plane.code.toString(),
                s.offset == null ? "G54" : s.offset.toString(),
                s.feedMode == null ? "G94" : s.feedMode.toString(),
                s.currentMotionMode == null ? null : s.currentMotionMode.toString(),
                s.feedRate,
                s.spindle == null ? "M5" : s.spindle.toString(),
                s.spindleSpeed);
    }

    public boolean isMetric() {
        return !"G20".equals(units);
    }

    public boolean isIncremental() {
        return "G91".equals(distance);
    }

    public boolean spindleOn() {
        return "M3".equals(spindle) || "M4".equals(spindle);
    }

    /** The G-code that restores units, distance mode (always absolute for a resume), plane, feed mode and WCS. */
    public String restoreCode() {
        return units + " G90 " + plane + " " + feedMode + " " + wcs;
    }

    /** As the journal's {@code modal} object (job-state schema). */
    public JobState.ModalState toJournal() {
        JobState.ModalState m = new JobState.ModalState();
        m.units = units;
        m.distance = distance;
        m.plane = plane;
        m.wcs = wcs;
        m.feedMode = "G95".equals(feedMode) ? null : feedMode;
        m.motion = motion;
        m.feed = feed;
        m.spindle = spindle;
        return m;
    }
}

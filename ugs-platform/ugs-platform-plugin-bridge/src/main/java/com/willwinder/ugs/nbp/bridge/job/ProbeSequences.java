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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Probe G-code generators (SPEC §11.4): two passes (fast, retract, slow), each bounded by the profile's
 * {@code max_travel}. Return command lists only; nothing is sent. The caller must check the probe input is clear
 * ({@code Pn} without the profile's pin letter) before sending, and read the trigger position from the
 * {@code [PRB:...]} report of the slow pass ({@link ProbeSequence#slowProbeIndex()}).
 *
 * <p>The probe moves are incremental ({@code G91}); every sequence ends with {@code G90}. If a probe fails
 * (alarm), the controller is left in G91: the caller must reset/restore modal state.
 */
public final class ProbeSequences {

    /**
     * @param commands        lines to send, in order
     * @param fastProbeIndex  index of the fast {@code G38.2}
     * @param slowProbeIndex  index of the slow {@code G38.2}: its PRB Z is the measurement (machine coords)
     * @param probePin        pin letter that must be clear in {@code Pn:} before starting (e.g. {@code P})
     */
    public record ProbeSequence(List<String> commands, int fastProbeIndex, int slowProbeIndex, String probePin,
                                List<String> warnings) {
    }

    private ProbeSequences() {
    }

    /** Measure the tool in the collet on the BitSetter. */
    public static ProbeSequence bitsetterMeasure(MachineProfile profile) {
        MachineProfile.BitSetterConfig b = profile.bitsetter;
        if (b == null || b.xy == null || b.maxTravel == null || b.fastFeed == null || b.slowFeed == null || b.retract == null) {
            throw new IllegalStateException("profile.bitsetter needs xy, max_travel, fast_feed, slow_feed and retract");
        }
        MachineProfile.Envelope env = profile.envelopeMm;
        double safe = profile.safeZ();
        double start = b.startZMachine != null ? b.startZMachine : safe;
        List<String> warnings = new ArrayList<>();
        if (env != null && env.min != null) {
            if (!env.contains(b.xy[0], b.xy[1], start, 1e-3) || !env.inRange(safe, 2, 1e-3)) {
                throw new IllegalStateException("BitSetter position or start Z is outside the envelope");
            }
        }
        double travel = b.maxTravel;
        if (env != null && env.min != null) {
            travel = Math.min(travel, start - env.min[2]);
        }
        if (travel <= 0) {
            throw new IllegalStateException("no probe travel left below the BitSetter start height");
        }
        double slowTravel = Math.min(b.retract + 1.0, travel);
        if (Boolean.FALSE.equals(b.verified)) {
            warnings.add("BitSetter position in the profile is not verified");
        }
        List<String> c = new ArrayList<>();
        c.add("G21 G90");
        c.add("G53 G0 Z" + f3(safe));
        c.add("G53 G0 X" + f3(b.xy[0]) + " Y" + f3(b.xy[1]));
        if (Math.abs(start - safe) > 1e-9) {
            c.add("G53 G0 Z" + f3(start));
        }
        c.add("G91");
        int fast = c.size();
        c.add("G38.2 Z-" + f3(travel) + " F" + ResumePlanner.feed(b.fastFeed));
        c.add("G0 Z" + f3(b.retract));
        int slow = c.size();
        c.add("G38.2 Z-" + f3(slowTravel) + " F" + ResumePlanner.feed(b.slowFeed));
        c.add("G90");
        c.add("G53 G0 Z" + f3(safe));
        return new ProbeSequence(List.copyOf(c), fast, slow, b.pin == null ? "P" : b.pin, warnings);
    }

    /**
     * BitZero Z only, at the current XY over the plate: probes down and sets work Z so the plate top is
     * {@code plate_thickness} (G10 L20).
     */
    public static ProbeSequence bitzeroZ(MachineProfile profile, String wcs) {
        MachineProfile.BitZeroConfig z = profile.bitzero;
        if (z == null || z.maxTravel == null || z.fastFeed == null || z.slowFeed == null || z.retract == null) {
            throw new IllegalStateException("profile.bitzero needs max_travel, fast_feed, slow_feed and retract");
        }
        if (z.plateThickness == null) {
            throw new IllegalStateException("BitZero plate thickness is not measured (profile.bitzero.plate_thickness)");
        }
        List<String> warnings = new ArrayList<>();
        if (!Boolean.TRUE.equals(z.plateThicknessVerified)) {
            warnings.add("BitZero plate thickness is not verified");
        }
        double slowTravel = Math.min(z.retract + 1.0, z.maxTravel);
        List<String> c = new ArrayList<>();
        c.add("G21 G91");
        int fast = c.size();
        c.add("G38.2 Z-" + f3(z.maxTravel) + " F" + ResumePlanner.feed(z.fastFeed));
        c.add("G0 Z" + f3(z.retract));
        int slow = c.size();
        c.add("G38.2 Z-" + f3(slowTravel) + " F" + ResumePlanner.feed(z.slowFeed));
        c.add(String.format(Locale.ROOT, "G10 L20 P%d Z%.3f", BitSetter.wcsIndex(wcs), z.plateThickness));
        c.add("G0 Z" + f3(z.retract));
        c.add("G90");
        return new ProbeSequence(List.copyOf(c), fast, slow, z.pin == null ? "P" : z.pin, warnings);
    }

    /**
     * One axis of a bore-center probe: from inside the bore, probe the minus wall (fast, retract, slow, retract),
     * then the plus wall the same way, then {@code G90}. The slow probes' PRB values on this axis are the two wall
     * contacts (machine coordinates).
     *
     * @param axis           {@code X} or {@code Y}
     * @param slowMinusIndex index of the slow probe toward the minus wall
     * @param slowPlusIndex  index of the slow probe toward the plus wall
     */
    public record BoreAxis(String axis, List<String> commands, int slowMinusIndex, int slowPlusIndex) {
    }

    /**
     * BitZero XY corner by the bore-center method (J-1). Either {@link #refusal} is set (nothing may be sent), or
     * the X and Y phases are. Run: check the probe pin is clear; send {@link #x}; move to the X center
     * ({@link #centerMove}); send {@link #y}; move to the Y center; check {@link #boreCheck} on both axes; set the
     * origin with {@link #setOrigin}. Z is set separately with {@link #bitzeroZ} with the bit over the plate top
     * (together: method {@code bitzero_xyz}).
     *
     * @param boreDiameter       profile {@code bitzero.bore_diameter}
     * @param boreCenterToCorner profile {@code bitzero.bore_center_to_corner} {@code [dx, dy]}
     * @param toolDiameter       diameter of the bit in the bore (mm)
     */
    public record CornerSequence(String refusal, BoreAxis x, BoreAxis y, double boreDiameter,
                                 double[] boreCenterToCorner, double toolDiameter, String probePin,
                                 List<String> warnings) {
        public boolean refused() {
            return refusal != null;
        }

        /** {@code G53 G0 <axis><center>}, center = mid-point of the two wall contacts (machine mm). */
        public static String centerMove(String axis, double minusContact, double plusContact) {
            return "G53 G0 " + axis + f3((minusContact + plusContact) / 2.0);
        }

        /**
         * Null when the bore measured from the two contacts ({@code plus − minus + tool}) is within
         * {@code tolerance} of the profile's bore diameter, else a message (wrong tool diameter, bit not in the
         * bore, chips).
         */
        public String boreCheck(String axis, double minusContact, double plusContact, double tolerance) {
            double measured = plusContact - minusContact + toolDiameter;
            if (Math.abs(measured - boreDiameter) <= tolerance) {
                return null;
            }
            return String.format(Locale.ROOT, "%s: measured bore %.3f mm, profile says %.3f mm (tool %.3f mm)", axis,
                    measured, boreDiameter, toolDiameter);
        }

        /** {@code G10 L2 P<n> X.. Y..}: the work XY origin at bore center + {@code bore_center_to_corner}. */
        public String setOrigin(String wcs, double centerX, double centerY) {
            return String.format(Locale.ROOT, "G10 L2 P%d X%.3f Y%.3f", BitSetter.wcsIndex(wcs),
                    centerX + boreCenterToCorner[0], centerY + boreCenterToCorner[1]);
        }
    }

    /**
     * BitZero XY corner (bore-center method). Refuses (no commands) until the profile has {@code bore_diameter} and
     * {@code bore_center_to_corner} (machine ledger B3), when the tool doesn't fit the bore, or when the feeds are
     * missing.
     */
    public static CornerSequence bitzeroCorner(MachineProfile profile, double toolDiameter) {
        MachineProfile.BitZeroConfig z = profile.bitzero;
        String pin = z == null || z.pin == null ? "P" : z.pin;
        if (z == null || z.fastFeed == null || z.slowFeed == null || z.retract == null) {
            return refuse("profile.bitzero needs fast_feed, slow_feed and retract", toolDiameter, pin);
        }
        if (z.boreDiameter == null || z.boreCenterToCorner == null || z.boreCenterToCorner.length != 2) {
            return refuse("BitZero XY corner probing needs bitzero.bore_diameter and bitzero.bore_center_to_corner "
                    + "[dx, dy] in the profile (machine ledger B3: not measured yet). Use Z-only probing.", toolDiameter, pin);
        }
        if (!(toolDiameter > 0) || toolDiameter >= z.boreDiameter - 0.5) {
            return refuse(String.format(Locale.ROOT, "tool diameter %.3f mm doesn't fit the %.3f mm bore",
                    toolDiameter, z.boreDiameter), toolDiameter, pin);
        }
        double r = z.retract;
        // From anywhere in the bore the far wall is at most bore - tool away; 1 mm extra, then GRBL alarms (bounded).
        double span = z.boreDiameter - toolDiameter + 1.0;
        if (r >= span) {
            return refuse("bitzero.retract is larger than the free space in the bore", toolDiameter, pin);
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("BitZero bore geometry comes from the profile; check it against ledger B3");
        return new CornerSequence(null, boreAxis("X", span, r, z), boreAxis("Y", span, r, z), z.boreDiameter,
                z.boreCenterToCorner.clone(), toolDiameter, pin, warnings);
    }

    private static BoreAxis boreAxis(String axis, double span, double r, MachineProfile.BitZeroConfig z) {
        double slowTravel = r + 1.0;
        String fast = " F" + ResumePlanner.feed(z.fastFeed);
        String slow = " F" + ResumePlanner.feed(z.slowFeed);
        List<String> c = new ArrayList<>();
        c.add("G21 G91");
        c.add("G38.2 " + axis + "-" + f3(span) + fast);
        c.add("G0 " + axis + f3(r));
        int minus = c.size();
        c.add("G38.2 " + axis + "-" + f3(slowTravel) + slow);
        c.add("G0 " + axis + f3(r));
        c.add("G38.2 " + axis + f3(span) + fast);
        c.add("G0 " + axis + "-" + f3(r));
        int plus = c.size();
        c.add("G38.2 " + axis + f3(slowTravel) + slow);
        c.add("G0 " + axis + "-" + f3(r));
        c.add("G90");
        return new BoreAxis(axis, List.copyOf(c), minus, plus);
    }

    private static CornerSequence refuse(String why, double toolDiameter, String pin) {
        return new CornerSequence(why, null, null, Double.NaN, null, toolDiameter, pin, List.of());
    }

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}

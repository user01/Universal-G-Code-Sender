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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Carbide-style tool-length math with a BitSetter (SPEC §11.4). All values mm, machine coordinates.
 *
 * <ul>
 *   <li>Reference: after zeroing Z on the stock with tool A (WCO_z) and measuring A on the BitSetter (trigger at
 *       machine Z {@code S_A}): {@code ref = WCO_z − S_A}.</li>
 *   <li>After a change to tool B triggering at {@code S_B}: {@code WCO_z = S_B + ref}, written with
 *       {@code G10 L2 P1 Z…}.</li>
 *   <li>Verify: re-measuring the same tool must reproduce S within {@code repeat_tolerance}.</li>
 *   <li>The reference is invalid once the BitSetter position (profile) or the homing configuration changes, or
 *       the spoilboard/BitSetter is moved (reported by the operator).</li>
 * </ul>
 */
public final class BitSetter {

    private BitSetter() {
    }

    /** {@code ref = WCO_z − S_tool}, rounded to 0.001 mm (GRBL's resolution; J-2). */
    public static double reference(double wcoZ, double triggerZ) {
        return round3(wcoZ - triggerZ);
    }

    /** {@code WCO_z = S_new + ref}, rounded to 0.001 mm. */
    public static double wcoZForTool(double triggerZ, double ref) {
        return round3(triggerZ + ref);
    }

    /** Rounds to 0.001 mm, the resolution GRBL reports and stores ({@code -7.520000000000003} gives {@code -7.52}). */
    public static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** {@link #round3(double)} per element; null stays null. */
    public static double[] round3(double[] v) {
        if (v == null) {
            return null;
        }
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = round3(v[i]);
        }
        return out;
    }

    /** {@code G10 L2 P<n> Z<wcoZ>} for G54 (P1) .. G59 (P6). */
    public static String setWcoZCommand(String wcs, double wcoZ) {
        return String.format(Locale.ROOT, "G10 L2 P%d Z%.3f", wcsIndex(wcs), wcoZ);
    }

    public static int wcsIndex(String wcs) {
        if (wcs == null) {
            return 1;
        }
        return switch (wcs.trim().toUpperCase(Locale.ROOT)) {
            case "G55" -> 2;
            case "G56" -> 3;
            case "G57" -> 4;
            case "G58" -> 5;
            case "G59" -> 6;
            default -> 1;
        };
    }

    /** Result of comparing two trigger heights of the same tool. */
    public record Repeat(boolean ok, double difference, double tolerance) {
    }

    public static Repeat verifyRepeat(double firstTriggerZ, double secondTriggerZ, double tolerance) {
        double d = Math.abs(firstTriggerZ - secondTriggerZ);
        return new Repeat(d <= tolerance + 1e-9, d, tolerance);
    }

    /**
     * A stored reference and what it depends on.
     *
     * @param ref              WCO_z − S
     * @param triggerZ         S of the tool it was taken with
     * @param tool             that tool
     * @param bitsetterXy      profile BitSetter XY when it was taken
     * @param homingSignature  identifies the homing configuration (e.g. the {@code $22..$27} values); null = unknown
     */
    public record Reference(double ref, double triggerZ, String tool, double[] bitsetterXy, String homingSignature) {
    }

    /**
     * Reasons the reference can no longer be used (empty = valid).
     *
     * @param spoilboardOrBitsetterMoved operator reported the spoilboard or the BitSetter moved
     */
    public static List<String> invalidationReasons(Reference r, MachineProfile profile, String homingSignature,
                                                   boolean spoilboardOrBitsetterMoved) {
        List<String> out = new ArrayList<>();
        if (r == null) {
            out.add("no reference measured");
            return out;
        }
        double[] xy = profile.bitsetter == null ? null : profile.bitsetter.xy;
        if (xy == null || r.bitsetterXy() == null || xy.length < 2
                || Math.abs(xy[0] - r.bitsetterXy()[0]) > 1e-6 || Math.abs(xy[1] - r.bitsetterXy()[1]) > 1e-6) {
            out.add("BitSetter position changed: " + Arrays.toString(r.bitsetterXy()) + " -> " + Arrays.toString(xy));
        }
        if (r.homingSignature() != null && !Objects.equals(r.homingSignature(), homingSignature)) {
            out.add("homing configuration changed");
        }
        if (spoilboardOrBitsetterMoved) {
            out.add("spoilboard or BitSetter moved");
        }
        return out;
    }
}

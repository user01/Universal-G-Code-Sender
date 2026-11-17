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
package com.willwinder.ugs.nbp.bridge.job.estimate;

import com.willwinder.ugs.nbp.bridge.job.MachineProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The GRBL motion limits the time estimate simulates.
 *
 * @param maxRateMmMin        {@code $110-$112}, mm/min
 * @param accelMmS2           {@code $120-$122}, mm/s²
 * @param junctionDeviationMm {@code $11}
 * @param arcToleranceMm      {@code $12}
 * @param plannerBlocks       planner depth (Carbide's GRBL build: 14)
 * @param source              {@code controller} ({@code $$} seen this session) or {@code profile}
 * @param assumptions         values that are defaults rather than read from the controller or profile
 */
public record MotionSettings(double[] maxRateMmMin, double[] accelMmS2, double junctionDeviationMm,
                             double arcToleranceMm, int plannerBlocks, String source, List<String> assumptions) {

    /** GRBL 1.1 defaults for {@code $11} and {@code $12}. */
    public static final double DEFAULT_JUNCTION_DEVIATION = 0.01;
    public static final double DEFAULT_ARC_TOLERANCE = 0.002;
    private static final double[] DEFAULT_RATE = {5000, 5000, 1000};
    private static final double[] DEFAULT_ACCEL = {400, 400, 200};

    public MotionSettings {
        assumptions = List.copyOf(assumptions);
    }

    /** From the profile's {@code motion} block (defaults, listed as assumptions, for what it lacks). */
    public static MotionSettings fromProfile(MachineProfile p, int plannerBlocks) {
        List<String> a = new ArrayList<>();
        MachineProfile.MotionLimits m = p == null ? null : p.motion;
        double[] rate = valid3(m == null ? null : m.maxRateMmMin);
        double[] accel = valid3(m == null ? null : m.accelMmS2);
        if (rate == null) {
            rate = DEFAULT_RATE.clone();
            a.add("max rates not in the profile: assumed " + fmt(rate) + " mm/min");
        }
        if (accel == null) {
            accel = DEFAULT_ACCEL.clone();
            a.add("accelerations not in the profile: assumed " + fmt(accel) + " mm/s²");
        }
        double jd = m != null && m.junctionDeviationMm != null && m.junctionDeviationMm >= 0 ? m.junctionDeviationMm : Double.NaN;
        if (Double.isNaN(jd)) {
            jd = DEFAULT_JUNCTION_DEVIATION;
            a.add("junction deviation ($11) assumed " + jd + " mm (GRBL default)");
        }
        double at = m != null && m.arcToleranceMm != null && m.arcToleranceMm > 0 ? m.arcToleranceMm : Double.NaN;
        if (Double.isNaN(at)) {
            at = DEFAULT_ARC_TOLERANCE;
            a.add("arc tolerance ($12) assumed " + at + " mm (GRBL default)");
        }
        return new MotionSettings(rate, accel, jd, at, plannerBlocks, "profile", a);
    }

    /**
     * From the controller's {@code $$} values ({@code "$110" -> 10000.0}); anything missing comes from the profile.
     * Returns the profile settings when the map lacks the rates or accelerations.
     */
    public static MotionSettings fromController(Map<String, Double> settings, MachineProfile p, int plannerBlocks) {
        MotionSettings base = fromProfile(p, plannerBlocks);
        if (settings == null) {
            return base;
        }
        double[] rate = read3(settings, 110);
        double[] accel = read3(settings, 120);
        if (rate == null || accel == null) {
            return base;
        }
        List<String> a = new ArrayList<>();
        Double jd = settings.get("$11");
        Double at = settings.get("$12");
        if (jd == null || jd < 0) {
            a.add("junction deviation ($11) not read: " + base.junctionDeviationMm() + " mm from the profile");
        }
        if (at == null || at <= 0) {
            a.add("arc tolerance ($12) not read: " + base.arcToleranceMm() + " mm from the profile");
        }
        return new MotionSettings(rate, accel, jd == null || jd < 0 ? base.junctionDeviationMm() : jd,
                at == null || at <= 0 ? base.arcToleranceMm() : at, plannerBlocks, "controller", a);
    }

    /** A key for caching: the values that change the estimate. */
    public String cacheKey() {
        return String.format(Locale.ROOT, "%s|%s|%.6f|%.6f|%d", Arrays.toString(maxRateMmMin), Arrays.toString(accelMmS2),
                junctionDeviationMm, arcToleranceMm, plannerBlocks);
    }

    private static double[] read3(Map<String, Double> s, int first) {
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            Double d = s.get("$" + (first + i));
            if (d == null || !(d > 0)) {
                return null;
            }
            v[i] = d;
        }
        return v;
    }

    private static double[] valid3(double[] v) {
        if (v == null || v.length != 3) {
            return null;
        }
        for (double d : v) {
            if (!(d > 0)) {
                return null;
            }
        }
        return v.clone();
    }

    private static String fmt(double[] v) {
        return String.format(Locale.ROOT, "%g/%g/%g", v[0], v[1], v[2]);
    }
}

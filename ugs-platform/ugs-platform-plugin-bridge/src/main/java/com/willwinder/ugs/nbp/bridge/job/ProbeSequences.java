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

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}

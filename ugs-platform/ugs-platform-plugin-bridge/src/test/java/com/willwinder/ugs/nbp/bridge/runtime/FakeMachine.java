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
package com.willwinder.ugs.nbp.bridge.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny machine for routine tests: tracks machine position for G53 moves and G91 G0/G38.2 moves, G54 for
 * {@code G10 L2 P1}, and answers BitSetter probes at {@link #triggerZ}. Every line is recorded in {@link #log}
 * ({@code $#} too).
 */
final class FakeMachine implements MachineIo {
    private static final Pattern AXIS = Pattern.compile("([XYZ])\\s*(-?[0-9.]+)");

    final List<String> log = new ArrayList<>();
    final double[] mpos = {-150, -300, -20};
    final double[] g54 = {-300, -350, -60};
    double triggerZ = -70.0;
    /** Added to the Z written by G10 (a controller that doesn't store what it was told). */
    double g10ZError = 0;
    boolean spindleOn = true;
    boolean incremental;
    /** Pin readings to return, in order; when empty, {@link #pinDefault}. */
    final Deque<Boolean> pins = new ArrayDeque<>();
    boolean pinDefault;
    /** Fail the first line that starts with this. */
    String failOn;
    boolean probeNoContact;
    double minZ = Double.POSITIVE_INFINITY;

    @Override
    public void send(String line) throws MachineException {
        log.add(line);
        if (failOn != null && line.startsWith(failOn)) {
            throw new MachineException("'" + line + "' -> error:9");
        }
        String u = line.toUpperCase();
        if (u.contains("M5")) {
            spindleOn = false;
        }
        if (u.contains("G91")) {
            incremental = true;
        }
        if (u.contains("G90")) {
            incremental = false;
        }
        if (u.startsWith("G10 L2 P1")) {
            Matcher m = AXIS.matcher(u.substring(9));
            while (m.find()) {
                int a = m.group(1).charAt(0) - 'X';
                g54[a] = Double.parseDouble(m.group(2)) + (a == 2 ? g10ZError : 0);
            }
            return;
        }
        if (u.contains("G53")) {
            move(u, false);
        } else if (u.startsWith("G0 ") || u.startsWith("G1 ")) {
            move(u, incremental);
        }
    }

    private void move(String u, boolean rel) {
        Matcher m = AXIS.matcher(u.replaceAll("G[0-9.]+", "").replaceAll("F[0-9.]+", ""));
        while (m.find()) {
            int a = m.group(1).charAt(0) - 'X';
            double v = Double.parseDouble(m.group(2));
            mpos[a] = rel ? mpos[a] + v : v;
        }
        minZ = Math.min(minZ, mpos[2]);
    }

    @Override
    public double[] probe(String line) throws MachineException {
        log.add(line);
        if (probeNoContact) {
            throw new MachineException("'" + line + "': the probe made no contact within its travel");
        }
        mpos[2] = triggerZ;
        minZ = Math.min(minZ, mpos[2]);
        return mpos.clone();
    }

    @Override
    public double[] readG54() {
        log.add("$#");
        return g54.clone();
    }

    @Override
    public double[] machinePosition() {
        return mpos.clone();
    }

    @Override
    public boolean pinActive(String pin) {
        return pins.isEmpty() ? pinDefault : pins.removeFirst();
    }
}

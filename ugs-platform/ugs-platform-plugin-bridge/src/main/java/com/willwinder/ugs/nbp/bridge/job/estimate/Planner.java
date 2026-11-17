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

import java.util.ArrayList;
import java.util.List;

/**
 * GRBL 1.1's motion planner ({@code planner.c}), reduced to what decides time: per-block nominal speed and
 * acceleration limited per axis, junction speeds from the junction deviation, and the reverse/forward passes that
 * give each block its entry and exit speed, then a trapezoid (or triangle) per block.
 *
 * <p>Speeds are mm/s, accelerations mm/s². A {@link #sync()} empties the planner (GRBL does this for {@code G4},
 * spindle and coolant changes, {@code M6}, probes, program end): the next block starts from rest and the previous
 * one ends at rest.
 *
 * <p>Look-ahead: GRBL only plans the blocks in its buffer, so a block's entry speed must allow a stop by the end of
 * the last buffered block. With {@code horizon} blocks of look-ahead each block's entry is limited by a stop within
 * itself and the next {@code horizon - 1} blocks (the executing block holds the remaining buffer slot), which
 * assumes the sender keeps the buffer full. {@code horizon <= 0} plans the whole segment (unlimited look-ahead).
 */
public final class Planner {
    /** GRBL's {@code MINIMUM_FEED_RATE} (mm/min). */
    static final double MIN_FEED_MM_MIN = 1.0;
    private static final double EPS_LEN = 1e-9;

    private final MotionSettings s;
    private final double[] accel;    // mm/s²
    private final double[] maxRate;  // mm/s
    private final int horizon;

    private final List<double[]> blocks = new ArrayList<>(); // {mm, nominal v, accel, maxEntrySq, startsFromRest}
    private final List<Integer> tags = new ArrayList<>();
    private double[] prevUnit;
    private double prevNominal;
    private boolean empty = true;

    public Planner(MotionSettings s, int horizon) {
        this.s = s;
        this.horizon = horizon;
        accel = s.accelMmS2().clone();
        maxRate = new double[3];
        for (int i = 0; i < 3; i++) {
            maxRate[i] = s.maxRateMmMin()[i] / 60.0;
        }
    }

    /** The planner empties: the next block starts from rest. */
    public void sync() {
        empty = true;
    }

    /**
     * Adds a straight move.
     *
     * @param delta  mm per axis
     * @param rapid  G0: the per-axis max rates set the speed
     * @param feedMmMin programmed feed (ignored for rapids)
     * @param tag    caller's id for the block (file line), returned with its time
     * @return false when the move is too short to be a block (GRBL drops it)
     */
    public boolean add(double[] delta, boolean rapid, double feedMmMin, int tag) {
        double mm = Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]);
        if (mm < EPS_LEN) {
            return false;
        }
        double[] unit = {delta[0] / mm, delta[1] / mm, delta[2] / mm};
        double blockAccel = limitByAxis(accel, unit);
        double rapidRate = limitByAxis(maxRate, unit);
        double nominal = rapid ? rapidRate : Math.min(Math.max(feedMmMin, MIN_FEED_MM_MIN) / 60.0, rapidRate);
        double maxEntrySq;
        boolean fromRest = empty;
        if (empty) {
            maxEntrySq = 0;
        } else {
            double junctionSq = junctionSpeedSq(prevUnit, unit);
            maxEntrySq = Math.min(junctionSq, Math.min(prevNominal * prevNominal, nominal * nominal));
        }
        blocks.add(new double[]{mm, nominal, blockAccel, maxEntrySq, fromRest ? 1 : 0});
        tags.add(tag);
        prevUnit = unit;
        prevNominal = nominal;
        empty = false;
        return true;
    }

    /** Max junction speed² between two unit vectors (GRBL 1.1 {@code plan_buffer_line}). */
    double junctionSpeedSq(double[] prev, double[] cur) {
        double cos = 0;
        double[] ju = new double[3];
        for (int i = 0; i < 3; i++) {
            cos -= prev[i] * cur[i];
            ju[i] = cur[i] - prev[i];
        }
        if (cos > 0.999999) {
            return 0; // reversal: MINIMUM_JUNCTION_SPEED = 0
        }
        if (cos < -0.999999) {
            return Double.MAX_VALUE; // straight on
        }
        double n = Math.sqrt(ju[0] * ju[0] + ju[1] * ju[1] + ju[2] * ju[2]);
        for (int i = 0; i < 3; i++) {
            ju[i] /= n;
        }
        double ja = limitByAxis(accel, ju);
        double sinThetaD2 = Math.sqrt(0.5 * (1.0 - cos));
        return Math.max(0, ja * s.junctionDeviationMm() * sinThetaD2 / (1.0 - sinThetaD2));
    }

    static double limitByAxis(double[] max, double[] unit) {
        double limit = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (unit[i] != 0) {
                limit = Math.min(limit, Math.abs(max[i] / unit[i]));
            }
        }
        return limit;
    }

    public int size() {
        return blocks.size();
    }

    public int tag(int i) {
        return tags.get(i);
    }

    /** Seconds per block, in the order added. */
    public double[] times() {
        int n = blocks.size();
        double[] t = new double[n];
        int start = 0;
        while (start < n) {
            int end = start;
            while (end + 1 < n && blocks.get(end + 1)[4] == 0) {
                end++;
            }
            planSegment(start, end, t);
            start = end + 1;
        }
        return t;
    }

    /** Blocks start..end run without a stop between them; the first starts and the last ends at rest. */
    private void planSegment(int start, int end, double[] t) {
        int n = end - start + 1;
        double[] limit = new double[n]; // max entry speed² of each block, from the reverse pass
        if (horizon <= 0 || horizon >= n) {
            double next = 0;
            for (int k = n - 1; k >= 0; k--) {
                double[] b = blocks.get(start + k);
                next = Math.min(b[3], next + 2 * b[2] * b[0]);
                limit[k] = next;
            }
        } else {
            for (int j = 0; j < n; j++) {
                int last = Math.min(n - 1, j + horizon - 1);
                double next = 0;
                for (int k = last; k >= j; k--) {
                    double[] b = blocks.get(start + k);
                    next = Math.min(b[3], next + 2 * b[2] * b[0]);
                }
                limit[j] = next;
            }
        }
        double entrySq = 0;
        for (int k = 0; k < n; k++) {
            double[] b = blocks.get(start + k);
            double exitSq = k == n - 1 ? 0 : Math.min(limit[k + 1], entrySq + 2 * b[2] * b[0]);
            t[start + k] = trapezoid(b[0], Math.sqrt(entrySq), Math.sqrt(exitSq), b[1], b[2]);
            entrySq = exitSq;
        }
    }

    /** Seconds to cover {@code mm} from {@code v0} to {@code v1} (mm/s), cruising at most at {@code vn}. */
    static double trapezoid(double mm, double v0, double v1, double vn, double a) {
        v0 = Math.min(v0, vn);
        v1 = Math.min(v1, vn);
        double accelDist = (vn * vn - v0 * v0) / (2 * a);
        double decelDist = (vn * vn - v1 * v1) / (2 * a);
        if (accelDist + decelDist <= mm) {
            return (vn - v0) / a + (vn - v1) / a + (mm - accelDist - decelDist) / vn;
        }
        double peakSq = (2 * a * mm + v0 * v0 + v1 * v1) / 2;
        double peak = Math.sqrt(Math.max(peakSq, Math.max(v0 * v0, v1 * v1)));
        return Math.max(0, (peak - v0) / a) + Math.max(0, (peak - v1) / a);
    }
}

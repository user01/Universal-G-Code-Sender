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
 * Finds the earliest file line that may not have executed when a stream stopped (SPEC §11.6).
 *
 * <p>GRBL answers {@code ok} when a line enters the planner, not when it runs. So:
 * <ol>
 *   <li>Lines {@code <= line_done} ran (the journal's conservative mark).</li>
 *   <li>Of the acknowledged lines, at most the last {@link PlannerModel#plannerBlocks()} motion lines can still be
 *       in the planner (arcs count as one block, which backs up further than GRBL's real arc segmentation: safe).
 *       With {@link PlannerModel#ackMeansSent()} the lines that fit in the RX buffer are backed over first.</li>
 *   <li>The window is {@code [max(line_done+1, back-off), line_acked]}. If the journal has a last machine
 *       position and a WCO, the position (machine − WCO = work) is matched against the window's path segments;
 *       the first segment within tolerance is the line that was running. Without a position (power cut) or
 *       without a match, the window start is used: everything that might not have run is re-run.</li>
 * </ol>
 * The result is not snapped to a safe point; {@link ResumePlanner} does that.
 */
public final class ResumePointFinder {

    /**
     * @param earliestUnexecuted first line that may not have run (lineCount+1 if the job completed)
     * @param windowStart        first line of the uncertainty window
     * @param windowEnd          last acknowledged line
     * @param method             {@code position}, {@code buffer_backoff} or {@code completed}
     * @param matchedLine        line whose segment the position matched, or 0
     * @param matchDistanceMm    distance of the match, NaN if none
     */
    public record Result(int earliestUnexecuted, int windowStart, int windowEnd, String method, int matchedLine,
                         double matchDistanceMm, List<String> notes) {
    }

    private ResumePointFinder() {
    }

    public static Result find(JobModel job, JobState journal, PlannerModel pm) {
        int n = job.lineCount();
        List<String> notes = new ArrayList<>();
        int acked = clamp(journal.lineAcked, 0, n);
        int done = clamp(journal.lineDone, 0, acked);
        if ("completed".equals(journal.stream) && done >= n) {
            return new Result(n + 1, n + 1, n, "completed", 0, Double.NaN, List.of("Journal says the job completed"));
        }
        int backoff = backoffStart(job, acked, pm);
        int lo = Math.min(Math.max(done + 1, backoff), acked + 1);

        if (journal.lastMpos != null && journal.wco != null) {
            double[] work = {
                    journal.lastMpos[0] - journal.wco[0],
                    journal.lastMpos[1] - journal.wco[1],
                    journal.lastMpos[2] - journal.wco[2]};
            for (int i = lo; i <= acked; i++) {
                if (job.motion(i) == JobModel.Motion.NONE || job.isG53(i)) {
                    continue;
                }
                double d = distanceMm(job, i, work);
                if (d <= pm.positionToleranceMm()) {
                    notes.add(String.format(Locale.ROOT, "Last position (work X%.3f Y%.3f Z%.3f mm) is on line %d (%.3f mm)",
                            work[0], work[1], work[2], i, d));
                    return new Result(i, lo, acked, "position", i, d, notes);
                }
            }
            notes.add(String.format(Locale.ROOT,
                    "Last position (work X%.3f Y%.3f Z%.3f mm) is not on the path of lines %d..%d; backing up past the buffers",
                    work[0], work[1], work[2], lo, acked));
        } else if (journal.lastMpos != null) {
            notes.add("Journal has a position but no WCO; can't match it to the path");
        } else {
            notes.add("No last position (power cut?): backing up past every line that might not have run");
        }
        return new Result(lo, lo, acked, "buffer_backoff", 0, Double.NaN, notes);
    }

    /**
     * Earliest line that may still have been buffered (RX buffer and planner) when {@code lineAcked} was the last
     * acknowledged line. Returns 1 if fewer than a planner's worth of motion lines precede it.
     */
    public static int backoffStart(JobModel job, int lineAcked, PlannerModel pm) {
        int i = Math.min(lineAcked, job.lineCount());
        if (i < 1) {
            return 1;
        }
        int earliest = i + 1;
        if (pm.ackMeansSent()) {
            int bytes = 0;
            while (i >= 1) {
                if (job.hasCode(i)) {
                    int len = JobModel.stripComments(job.line(i)).length() + 1;
                    if (bytes + len > pm.rxBufferBytes()) {
                        break;
                    }
                    bytes += len;
                }
                earliest = i;
                i--;
            }
        }
        int blocks = 0;
        while (i >= 1) {
            if (job.motion(i) != JobModel.Motion.NONE) {
                blocks++;
                earliest = i;
                if (blocks >= pm.plannerBlocks()) {
                    return earliest;
                }
            }
            i--;
        }
        return 1;
    }

    /** Distance in mm from a work point (mm) to the path of {@code line}. */
    static double distanceMm(JobModel job, int line, double[] workMm) {
        double[] a = job.startPosition(line);
        double[] b = job.endPosition(line);
        double k = job.modalAt(line + 1).isMetric() ? 1 : 25.4;
        double ka = job.modalAt(line).isMetric() ? 1 : 25.4;
        for (int j = 0; j < 3; j++) {
            a[j] *= ka;
            b[j] *= k;
        }
        // An unknown start axis (first move, after G53) — measure from the end point on that axis.
        for (int j = 0; j < 3; j++) {
            if (Double.isNaN(a[j])) {
                a[j] = b[j];
            }
        }
        if (Double.isNaN(b[0]) || Double.isNaN(b[1]) || Double.isNaN(b[2])) {
            return Double.POSITIVE_INFINITY;
        }
        double[] arc = job.arcCenter(line);
        JobModel.Motion m = job.motion(line);
        if (arc != null && arc[3] == 17 && (m == JobModel.Motion.ARC_CW || m == JobModel.Motion.ARC_CCW)) {
            return distanceToArc(a, b, arc[0] * k, arc[1] * k, m == JobModel.Motion.ARC_CW, workMm);
        }
        return distanceToSegment(a, b, workMm);
    }

    static double distanceToSegment(double[] a, double[] b, double[] p) {
        double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double len2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
        double t = 0;
        if (len2 > 0) {
            t = ((p[0] - a[0]) * ab[0] + (p[1] - a[1]) * ab[1] + (p[2] - a[2]) * ab[2]) / len2;
            t = Math.max(0, Math.min(1, t));
        }
        double dx = a[0] + t * ab[0] - p[0];
        double dy = a[1] + t * ab[1] - p[1];
        double dz = a[2] + t * ab[2] - p[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** XY-plane arc (helical in Z), approximated by a polyline with chords of at most 0.25 mm. */
    static double distanceToArc(double[] a, double[] b, double cx, double cy, boolean cw, double[] p) {
        double r = Math.hypot(a[0] - cx, a[1] - cy);
        double a0 = Math.atan2(a[1] - cy, a[0] - cx);
        double a1 = Math.atan2(b[1] - cy, b[0] - cx);
        double sweep = cw ? a0 - a1 : a1 - a0;
        while (sweep <= 1e-9) {
            sweep += 2 * Math.PI;
        }
        int segs = (int) Math.min(2000, Math.max(8, Math.ceil(sweep * r / 0.25)));
        double best = Double.POSITIVE_INFINITY;
        double[] prev = a;
        for (int s = 1; s <= segs; s++) {
            double f = (double) s / segs;
            double ang = a0 + (cw ? -1 : 1) * sweep * f;
            double[] cur = s == segs ? b : new double[]{cx + r * Math.cos(ang), cy + r * Math.sin(ang), a[2] + (b[2] - a[2]) * f};
            best = Math.min(best, distanceToSegment(prev, cur, p));
            prev = cur;
        }
        return best;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

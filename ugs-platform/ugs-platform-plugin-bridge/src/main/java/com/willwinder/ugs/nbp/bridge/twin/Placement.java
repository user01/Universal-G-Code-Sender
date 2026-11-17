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
package com.willwinder.ugs.nbp.bridge.twin;

import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.ResumePlan;
import com.willwinder.ugs.nbp.bridge.job.ResumePlanner;
import com.willwinder.ugs.nbp.bridge.job.ToolInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the stock really is, and whether that's sane: the Java twin of the viewer's {@code twin/placement.js}, so the
 * checks apply with no viewer open (D27). The stock's machine box is the live WCO (G54) plus the stock box relative
 * to work zero. Checks, by kind:
 * <ul>
 *   <li>{@code stock_on_bitsetter}: the stock covers the BitSetter (profile {@code bitsetter.xy}, keep-out radius
 *       {@code bed.bitsetter_radius}, default 20 mm);</li>
 *   <li>{@code stock_off_spoilboard}: the stock reaches past the spoilboard ({@code bed.spoilboard});</li>
 *   <li>{@code cut_outside_stock}: a move below the stock top runs outside the stock in XY by more than its tool's
 *       diameter + 1 mm (arcs sampled);</li>
 *   <li>{@code outside_envelope}: the program, placed at the WCO, leaves the machine envelope.</li>
 * </ul>
 * Both implementations are held to the same expectations by {@code bridge/testdata-public/twin-placement-cases.json}.
 */
public final class Placement {
    /** One finding: {@code kind} as listed above, a plain message, the first 1-based line when it concerns moves. */
    public record Item(String kind, String message, Integer line, boolean assumed) {
    }

    private Placement() {
    }

    /** The stock box in machine coordinates, [min, max]. */
    public static double[][] machineBox(StockSpec stock, double[] wco) {
        return new double[][]{
                {stock.min()[0] + wco[0], stock.min()[1] + wco[1], stock.min()[2] + wco[2]},
                {stock.max()[0] + wco[0], stock.max()[1] + wco[1], stock.max()[2] + wco[2]}};
    }

    static boolean rectHitsCircle(double[] min, double[] max, double[] c, double r) {
        double cx = Math.max(min[0], Math.min(c[0], max[0]));
        double cy = Math.max(min[1], Math.min(c[1], max[1]));
        return (cx - c[0]) * (cx - c[0]) + (cy - c[1]) * (cy - c[1]) < r * r;
    }

    static boolean rectInside(double[] amin, double[] amax, double[] bmin, double[] bmax) {
        double eps = 0.01;
        return amin[0] >= bmin[0] - eps && amin[1] >= bmin[1] - eps && amax[0] <= bmax[0] + eps && amax[1] <= bmax[1] + eps;
    }

    /**
     * @param stock the job's stock, or null (then only the envelope is checked)
     * @param job   the loaded program, or null
     * @param wco   the live G54 offset (mm)
     */
    public static List<Item> check(TwinProfile profile, StockSpec stock, JobModel job, double[] wco) {
        List<Item> items = new ArrayList<>();
        if (stock != null) {
            double[][] box = machineBox(stock, wco);
            if (profile.bitsetterXy != null && rectHitsCircle(box[0], box[1], profile.bitsetterXy, profile.bitsetterRadius)) {
                items.add(new Item("stock_on_bitsetter", String.format(Locale.ROOT,
                        "The stock covers the BitSetter at X%s Y%s: a tool change would probe the stock.",
                        fmt(profile.bitsetterXy[0]), fmt(profile.bitsetterXy[1])), null, false));
            }
            if (profile.spoilboardMin != null && profile.spoilboardMax != null
                    && !rectInside(box[0], box[1], profile.spoilboardMin, profile.spoilboardMax)) {
                items.add(new Item("stock_off_spoilboard", "The stock reaches past the spoilboard"
                        + (profile.bedVerified ? "." : " (spoilboard size assumed, not measured)."), null, !profile.bedVerified));
            }
            if (job != null) {
                Item cut = cutOutside(stock, job);
                if (cut != null) {
                    items.add(cut);
                }
            }
        }
        if (job != null && profile.model != null && profile.model.envelopeMm != null) {
            ResumePlan.Check env = ResumePlanner.envelopeCheck(job, profile.model, 1, wco, new ArrayList<>());
            if (!env.ok && env.detail != null && !env.detail.startsWith("WCO unknown")) {
                items.add(new Item("outside_envelope", "At this work zero the program leaves the machine envelope: "
                        + env.detail + ".", null, !profile.model.envelopeMm.verified));
            }
        }
        return items;
    }

    private static Item cutOutside(StockSpec stock, JobModel job) {
        double top = stock.max()[2];
        int n = 0;
        Integer first = null;
        double worst = 0;
        for (int i = 1; i <= job.lineCount(); i++) {
            JobModel.Motion m = job.motion(i);
            if (m == JobModel.Motion.NONE || m == JobModel.Motion.PROBE || m == JobModel.Motion.OTHER || job.isG53(i)) {
                continue;
            }
            double k = job.modalAt(i).isMetric() ? 1 : 25.4;
            double[] a = scaled(job.startPosition(i), k);
            double[] b = scaled(job.endPosition(i), k);
            if (Double.isNaN(a[0]) || Double.isNaN(a[1]) || Double.isNaN(b[0]) || Double.isNaN(b[1])) {
                continue;
            }
            double za = Double.isNaN(a[2]) ? Double.POSITIVE_INFINITY : a[2];
            double zb = Double.isNaN(b[2]) ? Double.POSITIVE_INFINITY : b[2];
            if (za >= top && zb >= top) {
                continue;
            }
            ToolInfo t = job.tools().get(job.toolAt(i));
            double margin = (t == null || t.diameter() == null ? 0 : t.diameter()) + 1;
            double out = Math.max(outside(stock, b), outside(stock, a));
            if ((m == JobModel.Motion.ARC_CW || m == JobModel.Motion.ARC_CCW) && job.arcCenter(i) != null) {
                out = Math.max(out, arcOutside(stock, a, b, job.arcCenter(i), m == JobModel.Motion.ARC_CW, k));
            }
            if (out > margin) {
                n++;
                worst = Math.max(worst, out);
                if (first == null) {
                    first = i;
                }
            }
        }
        if (n == 0) {
            return null;
        }
        return new Item("cut_outside_stock", String.format(Locale.ROOT,
                "%d cutting move%s run outside the stock by up to %.1f mm (first at line %d): stock size or zero wrong?",
                n, n == 1 ? "" : "s", worst, first), first, false);
    }

    private static double[] scaled(double[] p, double k) {
        return new double[]{p[0] * k, p[1] * k, p[2] * k};
    }

    private static double outside(StockSpec s, double[] p) {
        return Math.max(Math.max(s.min()[0] - p[0], p[0] - s.max()[0]), Math.max(s.min()[1] - p[1], p[1] - s.max()[1]));
    }

    /** Worst XY distance outside the stock along an XY-plane arc, sampled every ~10°. */
    private static double arcOutside(StockSpec s, double[] a, double[] b, double[] center, boolean cw, double k) {
        if (center.length > 3 && center[3] != 17) {
            return 0;
        }
        double cx = center[0] * k;
        double cy = center[1] * k;
        double r = Math.hypot(a[0] - cx, a[1] - cy);
        double t0 = Math.atan2(a[1] - cy, a[0] - cx);
        double t1 = Math.atan2(b[1] - cy, b[0] - cx);
        double sweep = cw ? t0 - t1 : t1 - t0;
        while (sweep <= 1e-9) {
            sweep += 2 * Math.PI;
        }
        int steps = Math.max(2, (int) Math.ceil(sweep / Math.toRadians(10)));
        double worst = 0;
        for (int i = 1; i < steps; i++) {
            double t = t0 + (cw ? -1 : 1) * sweep * i / steps;
            worst = Math.max(worst, outside(s, new double[]{cx + r * Math.cos(t), cy + r * Math.sin(t)}));
        }
        return worst;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}

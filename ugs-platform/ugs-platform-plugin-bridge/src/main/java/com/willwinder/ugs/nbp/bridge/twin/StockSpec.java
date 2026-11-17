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
import com.willwinder.ugs.nbp.bridge.job.JobSidecar;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stock box of a job in work coordinates (mm), from the sidecar's {@code stock} or the {@code (STOCK: ...)} /
 * {@code (ZERO: ...)} header lines. A port of the viewer's {@code job.js} / {@code header.js} stock logic, so the
 * bridge and the viewer place the same box (D27).
 *
 * @param size          [x, y, z] mm
 * @param origin        where work zero sits on the stock, e.g. {@code front-left-top}
 * @param originAssumed true when no ZERO line said so (guessed)
 * @param source        {@code sidecar} or {@code header}
 * @param min           box minimum, work mm
 * @param max           box maximum, work mm
 */
public record StockSpec(double[] size, String origin, boolean originAssumed, String source, boolean approx,
                        String text, double[] min, double[] max) {

    private static final Pattern STOCK = Pattern.compile("^STOCK:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZERO = Pattern.compile("^(?:WORK\\s+)?ZERO\\b[^:]*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIMS = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*x\\s*(\\d+(?:\\.\\d+)?)(?:\\s*x\\s*(\\d+(?:\\.\\d+)?))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern THICK_RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)\\s*(?:mm\\s*)?thick", Pattern.CASE_INSENSITIVE);
    private static final Pattern THICK = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:mm\\s*)?thick", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPROX = Pattern.compile("at least|>=|≥|raw|min(?:imum)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCH = Pattern.compile("inch|\\bin\\b\\.?\\s*$", Pattern.CASE_INSENSITIVE);

    /** The job's stock, or null when neither the sidecar nor the header describes one. */
    public static StockSpec of(JobModel job, JobSidecar sidecar) {
        if (sidecar != null && sidecar.stock != null && sidecar.stock.size != null && sidecar.stock.size.length >= 3) {
            double k = "in".equalsIgnoreCase(sidecar.units) ? 25.4 : 1;
            double[] size = {sidecar.stock.size[0] * k, sidecar.stock.size[1] * k, sidecar.stock.size[2] * k};
            String origin = sidecar.stock.origin == null ? "front-left-top" : sidecar.stock.origin;
            return build(size, origin, false, "sidecar", false, null);
        }
        if (job == null) {
            return null;
        }
        String stockText = null;
        String zeroText = null;
        for (String raw : job.lines()) {
            String c = comment(raw);
            if (c == null) {
                continue;
            }
            Matcher m;
            if (stockText == null && (m = STOCK.matcher(c)).find()) {
                stockText = m.group(1);
            } else if (zeroText == null && (m = ZERO.matcher(c)).find()) {
                zeroText = m.group(1);
            }
            if (stockText != null && zeroText != null) {
                break;
            }
        }
        if (stockText == null) {
            return null;
        }
        Matcher d = DIMS.matcher(stockText);
        if (!d.find()) {
            return null;
        }
        Double z = d.group(3) != null ? Double.valueOf(d.group(3)) : null;
        boolean approx = APPROX.matcher(stockText).find();
        if (z == null) {
            Matcher r = THICK_RANGE.matcher(stockText);
            Matcher one = THICK.matcher(stockText);
            if (r.find()) {
                z = Double.parseDouble(r.group(2));
                approx = true;
            } else if (one.find()) {
                z = Double.parseDouble(one.group(1));
            }
        }
        double cutMinZ = cutMinZ(job);
        if (z == null || z == 0) {
            z = Double.isNaN(cutMinZ) ? 10 : Math.max(1, -cutMinZ);
        }
        double k = INCH.matcher(stockText).find() && !stockText.toLowerCase(Locale.ROOT).contains("mm") ? 25.4 : 1;
        double[] size = {Double.parseDouble(d.group(1)) * k, Double.parseDouble(d.group(2)) * k, z * k};
        String origin = zeroText == null ? null : zeroOrigin(zeroText);
        boolean assumed = origin == null;
        if (assumed) {
            // zero not on the stock (a jig top, say): if every cut is above Z0, guess the stock sits on Z0
            origin = !Double.isNaN(cutMinZ) && cutMinZ > 0 ? "front-left-bottom" : "front-left-top";
        }
        return build(size, origin, assumed, "header", approx, stockText.trim());
    }

    static StockSpec build(double[] size, String origin, boolean assumed, String source, boolean approx, String text) {
        double[][] box = box(size, origin);
        return new StockSpec(size, origin, assumed, source, approx, text, box[0], box[1]);
    }

    /** The stock box (work coords) for a stock of {@code size} placed by an origin like "front-left-top". */
    static double[][] box(double[] size, String origin) {
        String o = origin == null ? "front-left-top" : origin.toLowerCase(Locale.ROOT);
        boolean center = o.contains("center") || o.contains("middle");
        double x0 = o.contains("right") ? -size[0] : o.contains("left") ? 0 : center ? -size[0] / 2 : 0;
        double y0 = o.contains("back") || o.contains("rear") ? -size[1] : o.contains("front") ? 0 : center ? -size[1] / 2 : 0;
        double z0 = o.contains("bottom") ? 0 : -size[2];
        return new double[][]{{x0, y0, z0}, {x0 + size[0], y0 + size[1], z0 + size[2]}};
    }

    /** "front-left-top" style origin from a ZERO line, or null when the zero isn't on the stock. */
    static String zeroOrigin(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (!t.contains("stock")) {
            return null;
        }
        String fb = t.contains("front") ? "front" : t.contains("back") || t.contains("rear") ? "back" : "front";
        String lr = t.contains("left") ? "left" : t.contains("right") ? "right" : t.contains("center") ? "center" : "left";
        String tb = (t.contains("bottom") || t.contains("spoilboard")) && !t.contains("top") ? "bottom" : "top";
        return fb + "-" + lr + "-" + tb;
    }

    /** The text inside a whole-line {@code (...)} comment, trimmed, or null. */
    private static String comment(String raw) {
        String t = raw.trim();
        if (!t.startsWith("(") || !t.endsWith(")")) {
            return null;
        }
        return t.substring(1, t.length() - 1).trim();
    }

    /** Lowest Z (work, mm) of the cutting moves, NaN when there are none. */
    static double cutMinZ(JobModel job) {
        double min = Double.NaN;
        for (int i = 1; i <= job.lineCount(); i++) {
            JobModel.Motion m = job.motion(i);
            if (m != JobModel.Motion.LINEAR && m != JobModel.Motion.ARC_CW && m != JobModel.Motion.ARC_CCW) {
                continue;
            }
            if (job.isG53(i)) {
                continue;
            }
            double z = job.endPosition(i)[2] * (job.modalAt(i).isMetric() ? 1 : 25.4);
            if (!Double.isNaN(z) && (Double.isNaN(min) || z < min)) {
                min = z;
            }
        }
        return min;
    }

    /** One-line description: "120 × 80 × 12 mm (sidecar)". */
    public String label() {
        return String.format(Locale.ROOT, "%s × %s × %s mm (%s)", num(size[0]), num(size[1]), num(size[2]), source);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}

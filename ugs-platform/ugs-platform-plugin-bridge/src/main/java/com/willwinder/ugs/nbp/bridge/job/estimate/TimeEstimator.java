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

import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.Operation;
import com.willwinder.ugs.nbp.bridge.job.ToolInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Machine-time estimate of a G-code program by simulating GRBL 1.1's planner ({@link Planner}) over the moves the
 * controller would execute: arcs are split the way GRBL splits them ({@code $12}), rapids run at the per-axis max
 * rates, and dwells, spindle/coolant changes, tool changes, probes and program stops empty the planner.
 *
 * <p>Tool changes ({@code M6}) are reported, not counted as machine time: each gets the automatic cycle of the
 * bridge's BitSetter routine (park, spin-down wait, measure, return) estimated with the same planner, while the
 * operator's time is unknown. Feed overrides, the controller's step rounding and serial throughput are ignored, so a
 * file of very short segments sent faster than the serial link allows can run longer than estimated.
 */
public final class TimeEstimator {
    private static final double ARC_EPS = 5e-7;

    /**
     * @param settings      motion limits
     * @param wco           work offset (mm, machine = work + wco) or null when unknown (only matters for G53 moves)
     * @param startMachine  machine position at the start, or null: above work X0 Y0 at the profile's safe Z
     * @param profile       for the BitSetter cycle; may be null
     * @param fullLookahead plan with unlimited look-ahead instead of the controller's planner depth
     */
    public record Inputs(MotionSettings settings, double[] wco, double[] startMachine, MachineProfile profile,
                         boolean fullLookahead) {
    }

    private final JobModel job;
    private final Inputs in;
    private final List<String> assumptions = new ArrayList<>();
    private final Planner planner;
    private final double[] lineDwell;
    private final int[] lineTool;
    private double naive;

    // interpreter state
    private final double[] pos = new double[3];  // machine mm
    private final double[] wco;
    private final double[] g92 = new double[3];
    private double unit = 1;     // mm per program unit
    private boolean absolute = true;
    private int plane = 17;
    private int motion = 0;      // 0,1,2,3,38,80
    private double feed = 0;     // mm/min, or 1/min with G93
    private boolean inverseTime;
    private boolean spindleOn;
    private double speed;
    private int coolant = 9;
    private int pendingTool = -1;
    private int tool = 0;
    private final List<int[]> toolChanges = new ArrayList<>(); // {line, from(-1 unknown), to}
    private final List<Double> changeCycles = new ArrayList<>();
    private final Set<String> once = new LinkedHashSet<>();

    private TimeEstimator(JobModel job, Inputs in) {
        this.job = job;
        this.in = in;
        int depth = in.settings().plannerBlocks();
        planner = new Planner(in.settings(), in.fullLookahead() ? 0 : Math.max(1, depth - 1));
        lineDwell = new double[job.lineCount() + 1];
        lineTool = new int[job.lineCount() + 1];
        MachineProfile p = in.profile();
        if (in.wco() != null) {
            wco = in.wco().clone();
        } else {
            wco = defaultWco(p);
            assumptions.add(String.format(Locale.ROOT,
                    "work offset unknown: G53 moves timed with work zero at machine %.0f, %.0f, %.0f", wco[0], wco[1], wco[2]));
        }
        if (in.startMachine() != null) {
            System.arraycopy(in.startMachine(), 0, pos, 0, 3);
        } else {
            pos[0] = wco[0];
            pos[1] = wco[1];
            pos[2] = p == null ? -5 : p.safeZ();
            assumptions.add("starts above work X0 Y0 at the safe height");
        }
        if (in.fullLookahead()) {
            assumptions.add("unlimited planner look-ahead");
        } else {
            assumptions.add("planner look-ahead " + depth + " blocks, buffer kept full by the sender");
        }
        assumptions.add("feed and rapid overrides at 100%");
    }

    public static Estimate estimate(JobModel job, Inputs in) {
        return new TimeEstimator(job, in).run();
    }

    private static double[] defaultWco(MachineProfile p) {
        if (p != null && p.envelopeMm != null && p.envelopeMm.min != null && p.envelopeMm.max != null) {
            return new double[]{(p.envelopeMm.min[0] + p.envelopeMm.max[0]) / 2, (p.envelopeMm.min[1] + p.envelopeMm.max[1]) / 2,
                    p.envelopeMm.min[2] + 35};
        }
        return new double[]{-220, -225, -60};
    }

    private Estimate run() {
        int n = job.lineCount();
        for (int line = 1; line <= n; line++) {
            lineTool[line] = tool;
            String cmd = JobModel.stripComments(job.line(line));
            if (!cmd.isEmpty()) {
                try {
                    execute(line, cmd);
                } catch (RuntimeException e) {
                    note("line " + line + " not understood (" + e.getMessage() + "): not timed");
                }
            }
        }
        // Per-line seconds: planner blocks + dwells.
        double[] t = planner.times();
        double[] lineSec = lineDwell.clone();
        for (int i = 0; i < t.length; i++) {
            lineSec[planner.tag(i)] += t[i];
        }
        double[] lineEnd = new double[n];
        double cum = 0;
        Map<Integer, Double> perTool = new LinkedHashMap<>();
        for (int line = 1; line <= n; line++) {
            cum += lineSec[line];
            lineEnd[line - 1] = cum;
            if (lineSec[line] > 0) {
                perTool.merge(lineTool[line], lineSec[line], Double::sum);
            }
        }
        List<Estimate.Op> ops = new ArrayList<>();
        for (Operation op : job.operations()) {
            double start = op.startLine() <= 1 ? 0 : lineEnd[op.startLine() - 2];
            double end = lineEnd[Math.min(op.endLine(), n) - 1];
            ops.add(new Estimate.Op(op.index(), op.name(), op.tool(), op.startLine(), op.endLine(), start, end));
        }
        List<Estimate.Tool> tools = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : perTool.entrySet()) {
            int num = e.getKey();
            ToolInfo ti = job.tools().get(num);
            tools.add(new Estimate.Tool(num, num == 0 ? null : ti != null && ti.id() != null ? ti.id() : "T" + num,
                    ti == null ? null : ti.name(), e.getValue()));
        }
        List<Estimate.ToolChange> changes = new ArrayList<>();
        double bitsetter = 0;
        for (int k = 0; k < toolChanges.size(); k++) {
            int[] c = toolChanges.get(k);
            double at = c[0] <= 1 ? 0 : lineEnd[c[0] - 2];
            double cyc = changeCycles.get(k);
            bitsetter += cyc;
            changes.add(new Estimate.ToolChange(c[0], c[1] < 0 ? null : c[1], c[2], at, cyc));
        }
        assumptions.addAll(once);
        List<String> all = new ArrayList<>(in.settings().assumptions());
        all.addAll(assumptions);
        return new Estimate(job.sha256(), job.name(), n, in.settings(), lineEnd, cum, bitsetter, naive, ops, tools,
                changes, all);
    }

    private void note(String s) {
        if (once.size() < 20) {
            once.add(s);
        }
    }

    // ---------------------------------------------------------------- interpreter

    private void execute(int line, String cmd) {
        Words w = Words.parse(cmd);
        boolean g53 = false;
        boolean dwell = false;
        Integer nonModal = null; // 10, 28, 30, 92, 92.1
        Integer newMotion = null;
        for (double g : w.g) {
            int code = (int) Math.round(g * 10);
            switch (code) {
                case 0 -> newMotion = 0;
                case 10 -> newMotion = 1;
                case 20 -> newMotion = 2;
                case 30 -> newMotion = 3;
                case 382, 383, 384, 385 -> newMotion = 38;
                case 800 -> newMotion = 80;
                case 40 -> dwell = true;
                case 170 -> plane = 17;
                case 180 -> plane = 18;
                case 190 -> plane = 19;
                case 200 -> unit = 25.4;
                case 210 -> unit = 1;
                case 900 -> absolute = true;
                case 910 -> absolute = false;
                case 930 -> inverseTime = true;
                case 940 -> inverseTime = false;
                case 530 -> g53 = true;
                case 100 -> nonModal = 10;
                case 280, 300 -> nonModal = 28;
                case 920 -> nonModal = 92;
                case 921 -> nonModal = 921;
                case 540 -> {
                }
                case 550, 560, 570, 580, 590 -> note("work offsets other than G54 are taken as equal to G54");
                default -> {
                }
            }
        }
        if (w.has('F')) {
            feed = inverseTime ? w.get('F') : w.get('F') * unit;
        }
        if (w.has('S')) {
            double s = w.get('S');
            if (spindleOn && s != speed) {
                planner.sync();
            }
            speed = s;
        }
        if (w.has('T')) {
            pendingTool = (int) Math.round(w.get('T'));
        }
        for (double m : w.m) {
            int code = (int) Math.round(m);
            switch (code) {
                case 6 -> toolChange(line);
                case 3, 4 -> {
                    if (!spindleOn) {
                        planner.sync();
                    }
                    spindleOn = true;
                }
                case 5 -> {
                    if (spindleOn) {
                        planner.sync();
                    }
                    spindleOn = false;
                }
                case 7, 8, 9 -> {
                    if (coolant != code) {
                        planner.sync();
                    }
                    coolant = code;
                }
                case 0, 1, 2, 30 -> planner.sync();
                default -> {
                }
            }
        }
        if (dwell) {
            planner.sync();
            double p = w.has('P') ? Math.max(0, w.get('P')) : 0;
            lineDwell[line] += p;
            naive += p;
        }
        if (nonModal != null) {
            switch (nonModal) {
                case 10 -> setOffset(w);
                case 28 -> {
                    planner.sync();
                    note("G28/G30 moves are not timed (stored positions unknown)");
                }
                case 92 -> {
                    for (int a = 0; a < 3; a++) {
                        if (w.has(AXES[a])) {
                            g92[a] = pos[a] - wco[a] - w.get(AXES[a]) * unit;
                        }
                    }
                }
                case 921 -> {
                    g92[0] = 0;
                    g92[1] = 0;
                    g92[2] = 0;
                }
                default -> {
                }
            }
            if (nonModal == 10 || nonModal == 92 || nonModal == 28) {
                return; // axis words belonged to the non-modal command
            }
        }
        if (newMotion != null) {
            motion = newMotion;
        }
        if (!(w.has('X') || w.has('Y') || w.has('Z'))) {
            return;
        }
        double[] target = new double[3];
        for (int a = 0; a < 3; a++) {
            if (!w.has(AXES[a])) {
                target[a] = pos[a];
            } else if (g53) {
                target[a] = w.get(AXES[a]) * unit;
            } else if (absolute) {
                target[a] = w.get(AXES[a]) * unit + wco[a] + g92[a];
            } else {
                target[a] = pos[a] + w.get(AXES[a]) * unit;
            }
        }
        switch (motion) {
            case 0 -> linear(line, target, true);
            case 1 -> linear(line, target, false);
            case 2, 3 -> arc(line, target, w, motion == 2);
            case 38 -> {
                planner.sync();
                linear(line, target, false);
                planner.sync();
                note("probe moves (G38) timed over their full travel");
            }
            default -> {
            }
        }
    }

    private static final char[] AXES = {'X', 'Y', 'Z'};

    private void setOffset(Words w) {
        planner.sync();
        int l = w.has('L') ? (int) Math.round(w.get('L')) : 0;
        int p = w.has('P') ? (int) Math.round(w.get('P')) : 0;
        if (p != 0 && p != 1) {
            return; // not the active G54 (taken as equal)
        }
        for (int a = 0; a < 3; a++) {
            if (!w.has(AXES[a])) {
                continue;
            }
            double v = w.get(AXES[a]) * unit;
            if (l == 20) {
                wco[a] = pos[a] - g92[a] - v;
            } else if (l == 2) {
                wco[a] = v;
            }
        }
    }

    private void linear(int line, double[] target, boolean rapid) {
        double[] d = {target[0] - pos[0], target[1] - pos[1], target[2] - pos[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double f = rapid ? 0 : feedFor(len);
        if (planner.add(d, rapid, f, line)) {
            naive += naiveSeconds(d, len, rapid, f);
        }
        System.arraycopy(target, 0, pos, 0, 3);
    }

    private double feedFor(double len) {
        if (feed <= 0) {
            note("feed moves with no F are timed at 1 mm/min by GRBL's rule; check the file");
            return Planner.MIN_FEED_MM_MIN;
        }
        return inverseTime ? feed * len : feed;
    }

    private double naiveSeconds(double[] d, double len, boolean rapid, double f) {
        if (len <= 0) {
            return 0;
        }
        double[] u = {d[0] / len, d[1] / len, d[2] / len};
        double rate = Planner.limitByAxis(in.settings().maxRateMmMin(), u);
        return len / (rapid ? rate : Math.min(rate, Math.max(f, Planner.MIN_FEED_MM_MIN))) * 60.0;
    }

    /** GRBL's {@code mc_arc}: chords whose sagitta stays within {@code $12}. */
    private void arc(int line, double[] target, Words w, boolean clockwise) {
        int a0;
        int a1;
        int lin;
        switch (plane) {
            case 18 -> {
                a0 = 2;
                a1 = 0;
                lin = 1;
            }
            case 19 -> {
                a0 = 1;
                a1 = 2;
                lin = 0;
            }
            default -> {
                a0 = 0;
                a1 = 1;
                lin = 2;
            }
        }
        double[] off = new double[3];
        if (w.has('R')) {
            double r = w.get('R') * unit;
            double x = target[a0] - pos[a0];
            double y = target[a1] - pos[a1];
            double h = 4.0 * r * r - x * x - y * y;
            if (h < 0) {
                throw new IllegalArgumentException("arc radius too small for its end point");
            }
            double hd = -Math.sqrt(h) / Math.hypot(x, y);
            if (!clockwise) {
                hd = -hd;
            }
            if (r < 0) {
                hd = -hd;
            }
            off[a0] = 0.5 * (x - y * hd);
            off[a1] = 0.5 * (y + x * hd);
        } else {
            char[] ijk = {'I', 'J', 'K'};
            for (int a = 0; a < 3; a++) {
                off[a] = w.has(ijk[a]) ? w.get(ijk[a]) * unit : 0;
            }
        }
        double c0 = pos[a0] + off[a0];
        double c1 = pos[a1] + off[a1];
        double r0 = -off[a0];
        double r1 = -off[a1];
        double rt0 = target[a0] - c0;
        double rt1 = target[a1] - c1;
        double radius = Math.hypot(r0, r1);
        double ang = Math.atan2(r0 * rt1 - r1 * rt0, r0 * rt0 + r1 * rt1);
        if (clockwise) {
            if (ang >= -ARC_EPS) {
                ang -= 2 * Math.PI;
            }
        } else if (ang <= ARC_EPS) {
            ang += 2 * Math.PI;
        }
        double tol = in.settings().arcToleranceMm();
        int segments = (int) Math.floor(Math.abs(0.5 * ang * radius) / Math.sqrt(tol * (2 * radius - tol)));
        double linTravel = target[lin] - pos[lin];
        double arcLen = Math.hypot(Math.abs(ang) * radius, linTravel);
        double f = feedFor(arcLen);
        naive += arcLen / Math.max(f, Planner.MIN_FEED_MM_MIN) * 60.0;
        if (inverseTime) {
            f = feed * arcLen; // the whole arc takes 1/F minutes
        }
        if (segments > 1) {
            double theta = ang / segments;
            double linStep = linTravel / segments;
            double startLin = pos[lin];
            for (int i = 1; i < segments; i++) {
                double ct = Math.cos(i * theta);
                double st = Math.sin(i * theta);
                double[] p = new double[3];
                p[a0] = c0 + r0 * ct - r1 * st;
                p[a1] = c1 + r0 * st + r1 * ct;
                p[lin] = startLin + i * linStep;
                double[] d = {p[0] - pos[0], p[1] - pos[1], p[2] - pos[2]};
                planner.add(d, false, f, line);
                System.arraycopy(p, 0, pos, 0, 3);
            }
        }
        double[] d = {target[0] - pos[0], target[1] - pos[1], target[2] - pos[2]};
        planner.add(d, false, f, line);
        System.arraycopy(target, 0, pos, 0, 3);
    }

    // ---------------------------------------------------------------- tool changes

    private void toolChange(int line) {
        planner.sync();
        int to = pendingTool >= 0 ? pendingTool : tool;
        int from = toolChanges.isEmpty() && tool == 0 ? -1 : tool;
        toolChanges.add(new int[]{line, from, to});
        changeCycles.add(bitsetterCycle(from == to));
        tool = to;
        lineTool[line] = to;
    }

    /**
     * Seconds of the bridge's automatic tool-change cycle (ToolChangeRoutine): safe Z, park, spin-down wait, then
     * after the operator: BitSetter (fast probe, retract, slow probe) unless the same tool is re-loaded, safe Z,
     * back over the pre-change XY. Leaves {@link #pos} where the routine ends.
     */
    private double bitsetterCycle(boolean sameTool) {
        MachineProfile p = in.profile();
        if (p == null) {
            note("no machine profile: tool changes have no automatic cycle time");
            return 0;
        }
        double safe = p.safeZ();
        double[] pre = {pos[0], pos[1]};
        Planner c = new Planner(in.settings(), 0);
        double[] at = pos.clone();
        double dwell = p.spinDownSeconds();
        at = move(c, at, new double[]{at[0], at[1], safe}, true, 0);
        if (p.toolChangePark != null && p.toolChangePark.length >= 2) {
            at = move(c, at, new double[]{p.toolChangePark[0], p.toolChangePark[1], safe}, true, 0);
            if (p.toolChangePark.length >= 3 && Math.abs(p.toolChangePark[2] - safe) > 1e-9) {
                at = move(c, at, new double[]{at[0], at[1], p.toolChangePark[2]}, true, 0);
            }
        }
        c.sync();
        MachineProfile.BitSetterConfig b = p.bitsetter;
        if (!sameTool && b != null && b.xy != null && b.maxTravel != null) {
            double start = b.startZMachine == null ? safe : b.startZMachine;
            double travel = 0.75 * b.maxTravel;
            double retract = b.retract == null ? 2 : b.retract;
            at = move(c, at, new double[]{at[0], at[1], safe}, true, 0);
            at = move(c, at, new double[]{b.xy[0], b.xy[1], safe}, true, 0);
            at = move(c, at, new double[]{b.xy[0], b.xy[1], start}, true, 0);
            c.sync();
            at = move(c, at, new double[]{at[0], at[1], start - travel}, false, b.fastFeed == null ? 800 : b.fastFeed);
            c.sync();
            at = move(c, at, new double[]{at[0], at[1], at[2] + retract}, true, 0);
            c.sync();
            at = move(c, at, new double[]{at[0], at[1], at[2] - retract}, false, b.slowFeed == null ? 100 : b.slowFeed);
            c.sync();
            note("BitSetter cycle: fast probe assumed to travel 75% of max_travel");
        }
        at = move(c, at, new double[]{at[0], at[1], safe}, true, 0);
        at = move(c, at, new double[]{pre[0], pre[1], safe}, true, 0);
        System.arraycopy(at, 0, pos, 0, 3);
        double sum = dwell;
        for (double t : c.times()) {
            sum += t;
        }
        return sum;
    }

    private static double[] move(Planner c, double[] from, double[] to, boolean rapid, double f) {
        c.add(new double[]{to[0] - from[0], to[1] - from[1], to[2] - from[2]}, rapid, f, 0);
        return to.clone();
    }

    // ---------------------------------------------------------------- words

    /** The words of one line (comments already stripped). */
    static final class Words {
        final List<Double> g = new ArrayList<>(2);
        final List<Double> m = new ArrayList<>(1);
        private final double[] v = new double[26];
        private int present;

        static Words parse(String cmd) {
            Words w = new Words();
            int i = 0;
            int n = cmd.length();
            while (i < n) {
                char c = Character.toUpperCase(cmd.charAt(i));
                if (c < 'A' || c > 'Z') {
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < n && cmd.charAt(j) == ' ') {
                    j++;
                }
                int s = j;
                while (j < n && (Character.isDigit(cmd.charAt(j)) || "+-.".indexOf(cmd.charAt(j)) >= 0
                        || cmd.charAt(j) == ' ')) {
                    j++;
                }
                String num = cmd.substring(s, j).replace(" ", "");
                if (!num.isEmpty()) {
                    double d = Double.parseDouble(num);
                    if (c == 'G') {
                        w.g.add(d);
                    } else if (c == 'M') {
                        w.m.add(d);
                    } else {
                        w.v[c - 'A'] = d;
                        w.present |= 1 << (c - 'A');
                    }
                }
                i = Math.max(j, i + 1);
            }
            return w;
        }

        boolean has(char c) {
            return (present & (1 << (c - 'A'))) != 0;
        }

        double get(char c) {
            return v[c - 'A'];
        }
    }
}

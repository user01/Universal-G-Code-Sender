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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds a {@link ResumePlan} (SPEC §11.6) from a job, the machine profile, the journal and the current machine
 * context. Pure computation: nothing is sent.
 *
 * <p>Steps: resolve the target to a line; back up out of {@code G91} sections, modal-arc continuations and inline
 * {@code M6} lines; snap to the previous safe point (unless mid-contour was asked for); work out the tool the cut
 * needs, the checks (homed, origin, tool, tool length, BitSetter reference, envelope, clearance, plunge feed) and
 * {@code requires}; then the preamble:
 * <pre>
 *   G21 G53 G0 Z&lt;safe_z_machine&gt;          retract first, in mm whatever the controller's modal units are
 *   G21 G90 G17 G94 G54                     modal restore (file's units, plane, feed mode, WCS)
 *   G0 X.. Y..                              start point of the resume line
 *   G0 Z&lt;clearance&gt;
 *   S.. M3 / G4 P&lt;spin_up_s&gt;               only if the spindle is on at the resume line
 *   G1 Z&lt;start z&gt; F&lt;op plunge&gt;             only if the start is below clearance (G0 if above)
 *   G1 F&lt;modal feed&gt;                       modal motion + cutting feed in effect at the resume line
 * </pre>
 * The SPEC lists the modal restore before the {@code G53} retract; the retract goes first here so the tool leaves
 * the work before anything else, and carries its own {@code G21} so it can't be read as inches.
 */
public final class ResumePlanner {
    private static final double EPS = 1e-4;

    private ResumePlanner() {
    }

    public static ResumePlan plan(JobModel job, MachineProfile profile, JobState journal, ResumeContext ctx,
                                  ResumeTarget target) {
        ResumePlan p = new ResumePlan();
        p.fileSha256 = job.sha256();
        p.requested = target.toJson();
        int n = job.lineCount();

        JobState jr = journal;
        if (jr != null && jr.file != null && jr.file.sha256 != null && !jr.file.sha256.equals(job.sha256())) {
            p.checks.add(new ResumePlan.Check("journal_file", false,
                    "journal is for file " + jr.file.sha256 + ", this file is " + job.sha256()));
            p.warnings.add("The journal belongs to a different version of the file; it was ignored");
            jr = null;
        } else if (jr != null) {
            p.checks.add(new ResumePlan.Check("journal_file", true, "journal matches the file"));
        }

        // ---- 1. resolve the target
        int candidate;
        switch (target) {
            case ResumeTarget.Line l -> {
                if (l.line() < 1 || l.line() > n) {
                    throw new IllegalArgumentException("line " + l.line() + " is outside 1.." + n);
                }
                candidate = l.line();
            }
            case ResumeTarget.OperationName o -> candidate = job.findOperation(o.name())
                    .orElseThrow(() -> new IllegalArgumentException("unknown operation '" + o.name() + "'"))
                    .startLine();
            case ResumeTarget.Stopped s -> {
                if (jr == null) {
                    throw new IllegalStateException("no journal for this file: can't resume 'where it stopped'");
                }
                ResumePointFinder.Result r = ResumePointFinder.find(job, jr, ctx.planner);
                if (r.earliestUnexecuted() > n) {
                    throw new IllegalStateException("the journal says the job completed; nothing to resume");
                }
                p.finderMethod = r.method();
                candidate = r.earliestUnexecuted();
                p.checks.add(new ResumePlan.Check("resume_point", true, String.format(Locale.ROOT,
                        "acked %d, done %d: earliest line that may not have run is %d (%s, window %d..%d). %s",
                        jr.lineAcked, jr.lineDone, candidate, r.method(), r.windowStart(), r.windowEnd(),
                        String.join(" ", r.notes()))));
                if (r.method().equals("buffer_backoff") && jr.lastMpos != null) {
                    p.warnings.addAll(r.notes());
                }
            }
        }
        p.candidateLine = candidate;

        // ---- 2. adjust: G91, modal arcs, inline M6, safe point
        int line = adjust(job, candidate, ctx.midContour, p.warnings);
        p.resumeLine = line;
        p.safePoint = job.isSafePoint(line);
        if (!p.safePoint) {
            p.warnings.add("Line " + line + " is mid-contour (below clearance Z" + fmt(job.clearanceZ())
                    + "): the operator must confirm");
        }
        if (line != candidate) {
            p.warnings.add("Resuming at line " + line + " instead of " + candidate);
        }
        JobModel.Motion mo = job.motion(line);
        if (mo == JobModel.Motion.ARC_CW || mo == JobModel.Motion.ARC_CCW) {
            p.warnings.add("Line " + line + " is an arc: the preamble moves to its start point (the end of the line before)");
        }

        // ---- 3. tool and operation
        int firstCut = job.firstCuttingMotion(line);
        Integer m6InStream = null;
        if (firstCut > 0) {
            for (int i = line; i < firstCut; i++) {
                if (job.hasM6(i)) {
                    m6InStream = i;
                }
            }
        }
        p.toolChangeInStream = m6InStream;
        int toolNum = job.toolAt(firstCut > 0 ? firstCut : line);
        p.tool = toolNum > 0 ? job.toolId(toolNum) : null;
        Optional<Operation> op = job.operationAtOrAfter(line);
        p.operation = op.map(Operation::name).orElse(null);
        if (op.isPresent() && op.get().toolNumber() > 0 && toolNum > 0 && op.get().toolNumber() != toolNum) {
            p.warnings.add("Operation " + op.get().name() + " is labelled " + op.get().tool()
                    + " but T" + toolNum + " is loaded at line " + line);
        }

        // ---- 4. checks
        boolean needChange = false;
        boolean needMeasure = false;
        p.checks.add(new ResumePlan.Check("homed", ctx.homed,
                ctx.homed ? "homed in this connection" : "not homed in this connection"));
        if (!ctx.homed) {
            p.requires.add(ResumePlan.HOME);
        }

        String have = ctx.currentTool != null ? ctx.currentTool : jr != null ? jr.tool : null;
        int haveNum = ToolInfo.parseNumber(have);
        if (m6InStream != null) {
            p.checks.add(new ResumePlan.Check("tool", true, "M6 at line " + m6InStream + " (to " + p.tool
                    + ") runs in the stream; the tool-change interceptor measures the new tool"));
        } else if (toolNum <= 0) {
            p.checks.add(new ResumePlan.Check("tool", true, "the file loads no tool before this line; can't verify the collet"));
            p.warnings.add("No M6 before line " + line + ": check by eye that the right tool is in the collet");
        } else if (haveNum != toolNum) {
            needChange = true;
            needMeasure = true;
            p.checks.add(new ResumePlan.Check("tool", false, p.tool + " needed, collet has "
                    + (have == null ? "an unknown tool" : have)));
        } else {
            p.checks.add(new ResumePlan.Check("tool", true, p.tool + " in the collet"));
        }
        if (m6InStream == null && (ctx.powerLoss || !ctx.toolLengthKnown)) {
            needMeasure = true;
            p.checks.add(new ResumePlan.Check("tool_length", false, ctx.powerLoss
                    ? "power was lost: the tool length must be re-measured on the BitSetter"
                    : "tool length not measured since the controller reset"));
        } else {
            p.checks.add(new ResumePlan.Check("tool_length", !needMeasure,
                    needMeasure ? "new tool: measure on the BitSetter" : "tool length known"));
        }
        if (needChange) {
            p.requires.add(ResumePlan.TOOL_CHANGE);
        }
        if (needMeasure || m6InStream != null) {
            Double ref = ctx.bitsetterRef != null ? ctx.bitsetterRef : jr != null ? jr.bitsetterRef : null;
            boolean ok = ref != null;
            p.checks.add(new ResumePlan.Check("bitsetter_ref", ok, ok ? "ref = " + fmt(ref)
                    : "no BitSetter reference: Z for the measured tool can't be derived"));
            if (!ok) {
                p.warnings.add("No BitSetter reference: after measuring, Z must be re-zeroed (on uncut stock) or the reference restored");
            }
        }

        double[] journalWco = jr != null ? jr.wco : null;
        boolean compareZ = !needMeasure && m6InStream == null;
        double[] wco;
        if (journalWco == null) {
            wco = ctx.currentWco;
            if (wco == null) {
                p.checks.add(new ResumePlan.Check("origin", false, "neither the journal nor the controller has a WCO"));
                p.requires.add(ResumePlan.RESTORE_ORIGIN);
            } else {
                p.checks.add(new ResumePlan.Check("origin", true, "no journal origin to compare; using the current WCO "
                        + vec(wco)));
                p.warnings.add("No journal origin: confirm the current work zero is the job's zero");
            }
        } else {
            wco = journalWco;
            if (ctx.currentWco == null) {
                p.checks.add(new ResumePlan.Check("origin", false, "current WCO unknown; journal WCO " + vec(journalWco)));
                p.requires.add(ResumePlan.RESTORE_ORIGIN);
            } else if (!wcoMatches(journalWco, ctx.currentWco, ctx.wcoTolerance, compareZ)) {
                p.checks.add(new ResumePlan.Check("origin", false, "current WCO " + vec(ctx.currentWco)
                        + " differs from the journal's " + vec(journalWco) + (compareZ ? "" : " (XY compared; Z is set by the measurement)")));
                p.requires.add(ResumePlan.RESTORE_ORIGIN);
            } else {
                p.checks.add(new ResumePlan.Check("origin", true, "WCO matches the journal" + (compareZ ? "" : " in XY")));
            }
        }
        if (needMeasure) {
            p.requires.add(ResumePlan.TOOL_MEASURE);
        }
        if (!p.safePoint) {
            p.requires.add(ResumePlan.CONFIRM_MID_CONTOUR);
        }
        p.checks.add(new ResumePlan.Check("safe_point", p.safePoint, p.safePoint
                ? "tool at/above clearance or at an operation start" : "mid-contour"));

        boolean clearanceOk = !"inferred_max_z".equals(job.clearanceSource());
        p.checks.add(new ResumePlan.Check("clearance", clearanceOk, "Z" + fmt(job.clearanceZ()) + " from " + job.clearanceSource()));
        p.clearanceZWork = job.clearanceZ();

        // ---- 5. preamble
        Modal md = job.modalAt(line);
        double mmToFile = md.isMetric() ? 1 : 1 / 25.4;
        double[] s = job.startPosition(line);
        p.startPosition = java.util.Arrays.stream(s).mapToObj(v -> Double.isNaN(v) ? null : v).toArray(Double[]::new);
        Double plunge = op.isPresent() ? op.get().plungeFeed() : null;
        if (plunge == null) {
            Integer t = toolNum > 0 && job.tools().get(toolNum) != null ? toolNum : null;
            plunge = t == null ? null : job.tools().get(t).plungeFeed();
        }
        boolean needsPlunge = !Double.isNaN(s[2]) && s[2] < job.clearanceZ() - EPS;
        if (plunge == null && needsPlunge) {
            double f = md.feed() / mmToFile;
            plunge = f > 0 ? f / 2 : null;
            p.warnings.add("Plunge feed unknown for this operation: using half the modal feed"
                    + (plunge == null ? " (none set!)" : " (" + feed(plunge) + " mm/min)"));
        }
        p.plungeFeed = plunge;
        p.cutFeed = md.feed();
        p.checks.add(new ResumePlan.Check("plunge_feed", plunge != null || !needsPlunge,
                plunge == null ? (needsPlunge ? "unknown" : "no plunge needed") : feed(plunge) + " mm/min"));

        List<String> pre = p.preamble;
        pre.add("G21 G53 G0 Z" + fmt(profile.safeZ()));
        pre.add(md.restoreCode());
        StringBuilder xy = new StringBuilder("G0");
        if (!Double.isNaN(s[0])) {
            xy.append(" X").append(fmt(s[0]));
        }
        if (!Double.isNaN(s[1])) {
            xy.append(" Y").append(fmt(s[1]));
        }
        if (xy.length() > 2) {
            pre.add(xy.toString());
        }
        boolean zKnown = !Double.isNaN(s[2]);
        if (zKnown) {
            pre.add("G0 Z" + fmt(job.clearanceZ()));
        }
        if (md.spindleOn()) {
            pre.add((md.speed() > 0 ? "S" + feed(md.speed()) + " " : "") + md.spindle());
            // D15: the file's own dwell after its M3 (the operator's post emits G4 P2.0), else the profile's.
            Double fileDwell = profile.preferFileDwell() ? fileSpinUpDwell(job, line) : null;
            double spin = fileDwell != null ? fileDwell : profile.spinUpSeconds();
            p.spinUpSource = fileDwell != null ? "file" : "profile";
            if (spin > 0) {
                pre.add("G4 P" + feed(spin));
            }
        }
        if (zKnown) {
            if (needsPlunge) {
                pre.add("G1 Z" + fmt(s[2]) + (plunge != null ? " F" + feed(plunge * mmToFile) : ""));
            } else if (s[2] > job.clearanceZ() + EPS) {
                pre.add("G0 Z" + fmt(s[2]));
            }
        }
        String motion = md.motion();
        String f = md.feed() > 0 ? "F" + feed(md.feed()) : null;
        if ("G1".equals(motion) || "G0".equals(motion)) {
            pre.add(motion + (f != null ? " " + f : ""));
        } else if (f != null) {
            pre.add(f);
        }
        if ("G1".equals(motion) && f == null) {
            p.warnings.add("No feed rate is set at line " + line);
        }

        // ---- 6. envelope
        // J-1: when the tool is (re)measured before the cut, Z of the current WCO is not the Z the program will run
        // with; only XY is checked here and the tool-change routine checks Z with the post-measurement WCO.
        boolean zAfterMeasure = needMeasure || m6InStream != null;
        p.checks.add(envelopeCheck(job, profile, line, wco, p.warnings, !zAfterMeasure));

        p.ready = p.requires.isEmpty() && p.checks.stream().allMatch(c -> c.ok);
        p.planId = "rp-" + Json.sha256Hex((p.fileSha256 + "|" + p.resumeLine + "|" + String.join("\n", pre) + "|"
                + p.requires).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        return p;
    }

    /** Backs up out of G91 sections, modal-arc continuations and M6 lines, then snaps to a safe point. */
    static int adjust(JobModel job, int candidate, boolean midContour, List<String> warnings) {
        int line = candidate;
        boolean m6Handled = false;
        for (int iter = 0; iter < 20; iter++) {
            int before = line;
            if (!m6Handled && job.hasM6(line) && line > 1) {
                m6Handled = true;
                warnings.add("Line " + line + " has M6: resuming before it so the tool change runs again");
                line--;
            }
            if (job.modalAt(line).isIncremental()) {
                int k = line;
                while (k > 1 && job.modalAt(k).isIncremental()) {
                    k--;
                }
                warnings.add("Line " + line + " is in an incremental (G91) section: backing up to line " + k + " where it starts");
                line = k;
            }
            JobModel.Motion m = job.motion(line);
            if ((m == JobModel.Motion.ARC_CW || m == JobModel.Motion.ARC_CCW) && !job.hasExplicitMotionWord(line)) {
                int k = line;
                while (k > 1 && !job.hasExplicitMotionWord(k)) {
                    k--;
                }
                warnings.add("Line " + line + " continues a modal arc: backing up to line " + k + " which has the G2/G3 word");
                line = k;
            }
            if (!midContour) {
                int sp = job.previousSafePoint(line);
                line = Math.max(1, sp);
            }
            if (line == before) {
                break;
            }
        }
        return line;
    }

    /** All three axes: the remaining program from {@code line} with this WCO (mm) inside the profile's envelope. */
    public static ResumePlan.Check envelopeCheck(JobModel job, MachineProfile profile, int line, double[] wco,
                                                 List<String> warnings) {
        return envelopeCheck(job, profile, line, wco, warnings, true);
    }

    /**
     * @param checkZ false when the tool is measured before the cut: the current WCO's Z doesn't apply yet, so only
     *               X and Y are checked (the tool-change routine checks Z with the post-measurement WCO)
     */
    public static ResumePlan.Check envelopeCheck(JobModel job, MachineProfile profile, int line, double[] wco,
                                                 List<String> warnings, boolean checkZ) {
        MachineProfile.Envelope env = profile.envelopeMm;
        if (env == null || env.min == null || env.max == null) {
            return new ResumePlan.Check("envelope", false, "profile has no envelope");
        }
        double safe = profile.safeZ();
        if (!env.inRange(safe, 2, 1e-3)) {
            return new ResumePlan.Check("envelope", false, "safe_z_machine " + fmt(safe) + " is outside the envelope");
        }
        if (wco == null) {
            return new ResumePlan.Check("envelope", false, "WCO unknown: can't check moves against the envelope");
        }
        // Remaining program extents (work, mm) incl. the resume start point and the clearance plane.
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double clearMm = job.clearanceZ() * (job.modalAt(line).isMetric() ? 1 : 25.4);
        lo[2] = hi[2] = clearMm;
        for (int i = line; i <= job.lineCount() + 1; i++) {
            double[] s = job.startPosition(i);
            double k = job.modalAt(i).isMetric() ? 1 : 25.4;
            for (int a = 0; a < 3; a++) {
                if (!Double.isNaN(s[a])) {
                    lo[a] = Math.min(lo[a], s[a] * k);
                    hi[a] = Math.max(hi[a], s[a] * k);
                }
            }
        }
        List<String> bad = new ArrayList<>();
        String[] ax = {"X", "Y", "Z"};
        for (int a = 0; a < (checkZ ? 3 : 2); a++) {
            if (lo[a] == Double.POSITIVE_INFINITY) {
                continue;
            }
            double mlo = lo[a] + wco[a];
            double mhi = hi[a] + wco[a];
            if (mlo < env.min[a] - 1e-3 || mhi > env.max[a] + 1e-3) {
                bad.add(String.format(Locale.ROOT, "%s %.3f..%.3f outside %.3f..%.3f (machine)", ax[a], mlo, mhi,
                        env.min[a], env.max[a]));
            }
        }
        if (!env.verified) {
            warnings.add("The profile's envelope is not verified");
        }
        if (!bad.isEmpty()) {
            return new ResumePlan.Check("envelope", false, String.join("; ", bad));
        }
        if (!checkZ) {
            return new ResumePlan.Check("envelope", true, String.format(Locale.ROOT,
                    "remaining program inside the envelope in XY (machine X %.1f..%.1f Y %.1f..%.1f); Z is checked "
                            + "after the tool is measured", lo[0] + wco[0], hi[0] + wco[0], lo[1] + wco[1], hi[1] + wco[1]));
        }
        return new ResumePlan.Check("envelope", true, String.format(Locale.ROOT,
                "preamble and remaining program inside the envelope (machine X %.1f..%.1f Y %.1f..%.1f Z %.1f..%.1f)",
                lo[0] + wco[0], hi[0] + wco[0], lo[1] + wco[1], hi[1] + wco[1], lo[2] + wco[2], hi[2] + wco[2]));
    }

    private static final java.util.regex.Pattern SPINDLE_ON = java.util.regex.Pattern.compile("(?i)(?<![A-Z])M0?[34](?![0-9])");
    private static final java.util.regex.Pattern DWELL = java.util.regex.Pattern.compile(
            "(?i)(?<![A-Z])G0?4(?![0-9.]).*?(?<![A-Z])P\\s*([0-9]*\\.?[0-9]+)");

    /**
     * D15: the program's own spin-up dwell, i.e. the {@code G4 P<s>} on or within three code lines after the last
     * {@code M3}/{@code M4} before {@code line} (no motion in between). Null when there is none.
     */
    public static Double fileSpinUpDwell(JobModel job, int line) {
        for (int i = Math.min(line - 1, job.lineCount()); i >= 1; i--) {
            String code = stripComments(job.line(i));
            if (!SPINDLE_ON.matcher(code).find()) {
                continue;
            }
            int codeLines = 0;
            for (int k = i; k <= job.lineCount() && codeLines <= 3; k++) {
                String c = stripComments(job.line(k));
                if (c.isBlank()) {
                    continue;
                }
                java.util.regex.Matcher m = DWELL.matcher(c);
                if (m.find()) {
                    return Double.parseDouble(m.group(1));
                }
                if (k > i && job.motion(k) != JobModel.Motion.NONE) {
                    return null;
                }
                codeLines++;
            }
            return null;
        }
        return null;
    }

    private static String stripComments(String s) {
        return s.replaceAll("\\([^)]*\\)", " ").replaceAll(";.*$", "");
    }

    /** XY (and Z if {@code compareZ}) of two WCOs within {@code tol} mm. */
    public static boolean wcoMatches(double[] a, double[] b, double tol, boolean compareZ) {
        if (a == null || b == null) {
            return false;
        }
        int axes = compareZ ? 3 : 2;
        for (int i = 0; i < axes; i++) {
            if (Math.abs(a[i] - b[i]) > tol) {
                return false;
            }
        }
        return true;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static String feed(double v) {
        return BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
    }

    private static String vec(double[] v) {
        return String.format(Locale.ROOT, "[%.3f, %.3f, %.3f]", v[0], v[1], v[2]);
    }
}

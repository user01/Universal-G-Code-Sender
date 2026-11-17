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
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The estimator on the operator's files in {@code bridge/testdata/nc} (gitignored; skipped when absent). The
 * directory can be given with {@code -Dbridge.testdata.nc=<dir>}.
 */
public class RealFilesEstimateTest {

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("bridge/schema"))) {
            p = p.getParent();
        }
        return p;
    }

    private static Path dir() {
        String prop = System.getProperty("bridge.testdata.nc");
        Path d = prop != null ? Paths.get(prop) : repoRoot().resolve("bridge/testdata/nc");
        assumeTrue("no test data in " + d, Files.isDirectory(d));
        return d;
    }

    private static MachineProfile profile() throws IOException {
        return MachineProfile.read(repoRoot().resolve("bridge/profiles/shapeoko4-standard.json"));
    }

    /**
     * Air-cut run 2 (2026-11-16 16:54:20–16:55:36, ledger D2): 75.40 s stream, minus the two tool-change
     * interceptor windows (21.35 s and 19.42 s) = 34.63 s of machine time. Start MPos -3,-3,-3; G54 -345.05,-314,-47.245.
     */
    @Test
    public void airCutRunTwoMatchesTheLog() throws IOException {
        Path f = dir().resolve("aircut_T02_then_T21.nc");
        assumeTrue(Files.isRegularFile(f));
        MachineProfile p = profile();
        MotionSettings s = MotionSettings.fromProfile(p, 14);
        Estimate e = TimeEstimator.estimate(JobModel.load(f), new TimeEstimator.Inputs(s,
                new double[]{-345.05, -314, -47.245}, new double[]{-3, -3, -3}, p, false));
        double actual = 34.63;
        System.out.printf(Locale.ROOT, "aircut run 2: actual %.2f s, planner %.2f s (%+.1f%%), naive %.2f s (%+.1f%%), "
                        + "BitSetter cycles %.1f + %.1f s (actual 17.9 / 16.7 s without the spin-down wait)%n",
                actual, e.machineS(), 100 * (e.machineS() / actual - 1), e.naiveS(), 100 * (e.naiveS() / actual - 1),
                e.toolChanges().get(0).bitsetterS(), e.toolChanges().get(1).bitsetterS());
        assertEquals(actual, e.machineS(), actual * 0.05);
    }

    @Test
    public void everyRealFileEstimates() throws IOException {
        MachineProfile p = profile();
        MotionSettings s = MotionSettings.fromProfile(p, 14);
        List<Path> files;
        try (Stream<Path> st = Files.list(dir())) {
            files = st.filter(x -> x.toString().endsWith(".nc")).sorted().toList();
        }
        for (Path f : files) {
            JobModel job = JobModel.load(f);
            long t0 = System.nanoTime();
            Estimate e = TimeEstimator.estimate(job, new TimeEstimator.Inputs(s, null, null, p, false));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Estimate full = TimeEstimator.estimate(job, new TimeEstimator.Inputs(s, null, null, p, true));
            String name = f.getFileName().toString();
            System.out.printf(Locale.ROOT, "%-14s lines %6d  naive %7.1f min  planner %7.1f min  full-lookahead %7.1f min"
                            + "  tool changes %d (+%.1f min auto)  %d ms%n",
                    name.substring(0, Math.min(14, name.length())), job.lineCount(), e.naiveS() / 60, e.machineS() / 60,
                    full.machineS() / 60, e.toolChanges().size(), e.bitsetterS() / 60, ms);
            assertTrue(name, e.machineS() >= e.naiveS() * 0.999);
            assertTrue(name, e.machineS() >= full.machineS() - 1e-6);
            assertEquals(job.lineCount(), e.lineEndS().length);
        }
    }
}

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

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.junit.Assert.assertTrue;

/**
 * Writes sample JSON documents to {@code target/json-samples/} for schema validation outside Java:
 * <pre>
 * cd bridge && uv run python ../ugs-platform/ugs-platform-plugin-bridge/src/test/python/validate_json_samples.py \
 *     ../ugs-platform/ugs-platform-plugin-bridge/target/json-samples
 * </pre>
 * File name prefixes say which schema each sample is for ({@code job-state*}, {@code resume-plan*},
 * {@code origins*}).
 */
public class JsonSamplesTest {
    static final Path OUT = Paths.get("target", "json-samples");

    @Test
    public void writeSamples() throws IOException {
        Files.createDirectories(OUT);
        JobModel job = JobModel.parse("two.nc", Programs.TWO_TOOLS, null);
        MachineProfile profile = Programs.profile();

        // journal: full and minimal
        JobState full = JobState.start(job, "2026-11-15T12-00-00", Instant.parse("2026-11-15T12:00:00Z"));
        full.stream = "stopped";
        full.lineAcked = 14;
        full.lineDone = 12;
        full.lastMpos = new double[]{-280, -335, -62};
        full.wco = Programs.WCO.clone();
        full.tool = "T02";
        full.operation = "pocket";
        full.bitsetterRef = 31.25;
        full.originId = "o-1";
        full.modal = job.modalAt(14).toJournal();
        full.write(OUT.resolve("job-state-full.json"));
        JobState.start(job, null, Instant.parse("2026-11-15T12:00:00Z")).write(OUT.resolve("job-state-minimal.json"));

        // plans
        ResumeContext ctx = ResumeContext.homedWith(Programs.WCO.clone(), "T02");
        write("resume-plan-stopped.json", ResumePlanner.plan(job, profile, full, ctx, ResumeTarget.stopped()));
        write("resume-plan-toolchange.json", ResumePlanner.plan(job, profile, full, ctx, ResumeTarget.operation("finish")));
        ResumeContext bare = new ResumeContext();
        bare.midContour = true;
        bare.powerLoss = true;
        write("resume-plan-midcontour.json", ResumePlanner.plan(job, profile, null, bare, ResumeTarget.line(14)));

        // origins
        OriginStore store = new OriginStore();
        OriginStore.Origin o = new OriginStore.Origin("o-1", "G54", Programs.WCO.clone(), "bitzero_xyz",
                Instant.parse("2026-11-15T12:00:00Z"));
        o.name = "stock";
        o.tool = "T02";
        o.bitsetterRef = 31.25;
        o.session = "2026-11-15T12-00-00";
        o.jobSha256 = job.sha256();
        store.add(o);
        store.add(new OriginStore.Origin("o-2", "G54", new double[]{1, 2, 3}, "observed", Instant.parse("2026-11-15T13:00:00Z")));
        store.pin("o-1", job.sha256());
        store.write(OUT.resolve("origins.json"));
        new OriginStore().write(OUT.resolve("origins-empty.json"));

        assertTrue(Files.size(OUT.resolve("resume-plan-stopped.json")) > 0);
    }

    private static void write(String name, Object o) throws IOException {
        Json.writeAtomically(OUT.resolve(name), o);
    }
}

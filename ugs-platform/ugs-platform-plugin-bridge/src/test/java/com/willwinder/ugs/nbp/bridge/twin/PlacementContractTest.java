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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobSidecar;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The Java placement checks against the cases the viewer's {@code placement.js} is held to as well (D27). */
public class PlacementContractTest {
    static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("bridge/schema"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repo root not found");
        }
        return p;
    }

    @Test
    public void sameKindsAsTheViewerOnThePublicSample() throws Exception {
        Path pub = repoRoot().resolve("bridge/testdata-public");
        JsonObject spec = JsonParser.parseString(Files.readString(pub.resolve("twin-placement-cases.json"))).getAsJsonObject();
        TwinProfile profile = TwinProfile.parse(Files.readString(pub.resolve(spec.get("profile").getAsString()).normalize()));
        assertNotNull(profile);
        String program = Files.readString(pub.resolve(spec.get("program").getAsString()));
        String sidecarJson = Files.readString(pub.resolve(spec.get("sidecar").getAsString()));
        for (JsonElement e : spec.getAsJsonArray("cases")) {
            JsonObject c = e.getAsJsonObject();
            JobSidecar sc = JobSidecar.fromJson(sidecarJson);
            if (c.has("stock")) {
                JsonObject st = c.getAsJsonObject("stock");
                JsonArray size = st.getAsJsonArray("size");
                sc.stock.size = new double[]{size.get(0).getAsDouble(), size.get(1).getAsDouble(), size.get(2).getAsDouble()};
                sc.stock.origin = st.get("origin").getAsString();
            }
            JobModel job = JobModel.parse("sample-sign.nc", program, sc);
            StockSpec stock = StockSpec.of(job, sc);
            JsonArray w = c.getAsJsonArray("wco");
            double[] wco = {w.get(0).getAsDouble(), w.get(1).getAsDouble(), w.get(2).getAsDouble()};
            List<String> got = new ArrayList<>();
            for (Placement.Item i : Placement.check(profile, stock, job, wco)) {
                if (!i.kind().equals("outside_envelope")) {
                    got.add(i.kind());
                }
            }
            List<String> want = new ArrayList<>();
            c.getAsJsonArray("expect").forEach(x -> want.add(x.getAsString()));
            got.sort(null);
            want.sort(null);
            assertEquals(c.get("name").getAsString(), want, got);
        }
    }

    @Test
    public void envelopeIsCheckedAtTheWorkZero() throws Exception {
        Path pub = repoRoot().resolve("bridge/testdata-public");
        TwinProfile profile = TwinProfile.parse(Files.readString(repoRoot().resolve("bridge/profiles/shapeoko4-standard.json")));
        JobSidecar sc = JobSidecar.fromJson(Files.readString(pub.resolve("sample-sign.job.json")));
        JobModel job = JobModel.parse("sample-sign.nc", Files.readString(pub.resolve("sample-sign.nc")), sc);
        StockSpec stock = StockSpec.of(job, sc);
        List<Placement.Item> right = Placement.check(profile, stock, job, new double[]{-100, -300, -60});
        assertTrue(right.toString(), right.stream().anyMatch(i -> i.kind().equals("outside_envelope")));
        assertTrue(Placement.check(profile, stock, job, new double[]{-300, -300, -60}).isEmpty());
    }

    @Test
    public void stockFromTheHeaderWhenThereIsNoSidecar() throws Exception {
        Path pub = repoRoot().resolve("bridge/testdata-public");
        JobModel job = JobModel.parse("sample-sign.nc", Files.readString(pub.resolve("sample-sign.nc")), null);
        StockSpec s = StockSpec.of(job, null);
        assertNotNull(s);
        assertEquals("header", s.source());
        assertEquals("front-left-top", s.origin());
        assertEquals(120, s.size()[0], 1e-9);
        assertEquals(80, s.size()[1], 1e-9);
        assertEquals(12, s.size()[2], 1e-9);
        assertEquals(-12, s.min()[2], 1e-9);
        assertEquals(0, s.max()[2], 1e-9);
    }
}

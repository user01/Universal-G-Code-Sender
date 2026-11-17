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
package com.willwinder.ugs.nbp.bridge.notify;

import com.willwinder.ugs.nbp.bridge.eta.EstimateCache;
import com.willwinder.ugs.nbp.bridge.eta.EtaTracker;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.estimate.Estimate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Start, progress, tool-change and finish messages with the live estimate (D25). */
public class EtaNotificationsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(1000));
    final AtomicReference<NotifyConfig> config = new AtomicReference<>(NotifyConfig.defaults());
    EstimateCache cache;
    TriggerEngine engine;
    Path file;
    int cmd;

    @Before
    public void setUp() throws Exception {
        MachineProfile p = MachineProfile.fromJson("""
                {"safe_z_machine": -5, "tool_change_park": [-222, -440, -5], "planner_blocks": 14,
                 "motion": {"max_rate_mm_min": [10000, 10000, 1000], "accel_mm_s2": [500, 500, 270],
                            "junction_deviation_mm": 0.02, "arc_tolerance_mm": 0.01},
                 "spindle": {"spin_down_s": 8}}
                """);
        cache = new EstimateCache(() -> p);
        EtaTracker eta = new EtaTracker(cache, nanos::get,
                Clock.fixed(Instant.parse("2026-11-16T22:00:00Z"), ZoneOffset.ofHours(-5)));
        engine = new TriggerEngine(config::get, nanos::get, new TriggerEngine.JobModelTools(), eta);
        file = tmp.newFile("cut.nc").toPath();
        StringBuilder g = new StringBuilder("G21 G90\n(TOOL [FIRST]: T2 flat 1/4 in)\nT2 M6\n(---- OP 01 | T2 | rough | a ----)\n");
        for (int i = 0; i < 10; i++) {
            g.append("G1 X100 F600\nG1 X0\n"); // 10 × 20 s
        }
        g.append("M5\n(TOOL [SECOND]: T21 flat 1/8 in)\nT21 M6\n(---- OP 02 | T21 | finish | b ----)\n");
        for (int i = 0; i < 10; i++) {
            g.append("G1 X100 F600\nG1 X0\n");
        }
        g.append("M2\n");
        Files.writeString(file, g.toString());
    }

    @After
    public void tearDown() {
        cache.close();
    }

    List<TriggerEngine.Notification> feed(String kind, String data) {
        return engine.onRecord(TriggerEngineTest.rec(kind, nanos.get() / 1e9, data));
    }

    void ack(int line) {
        String id = "u-" + (++cmd);
        feed("tx", "{\"line\":\"x\",\"cmd_id\":\"" + id + "\",\"file_line\":" + (line + 1) + "}");
        feed("rx", "{\"line\":\"ok\",\"cmd_id\":\"" + id + "\"}");
    }

    @Test
    public void startProgressToolChangeAndFinish() {
        feed("event", "{\"type\":\"stream\",\"event\":\"started\"}");
        feed("file", "{\"action\":\"stream_start\",\"path\":\"" + file + "\",\"lines\":49}");
        Estimate e = cache.get(file, null, engine.eta().controller(), 10_000).orElseThrow();
        assertTrue("≈ 400 s of cutting: " + e.machineS(), e.machineS() > 400 && e.machineS() < 402);

        TriggerEngine.Notification start = TriggerEngineTest.one(engine.tick());
        assertEquals(Triggers.PROGRESS, start.trigger());
        assertTrue(start.text(), start.text().startsWith("▶ Started cut.nc: about 7 min of machine time + 2 tool changes (≈ "));
        assertTrue(start.text(), start.text().matches(".* automatic, plus your time\\)\\. Done ≈ 17:0\\d if the tool changes are quick\\."));
        assertTrue(engine.tick().isEmpty());

        // first tool done in 200 s: 50 % of the machine time, the second change is next
        for (int l = 1; l <= 24; l++) {
            ack(l);
        }
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(200));
        feed("status", "{\"state\":\"Idle\"}");
        TriggerEngine.Notification half = TriggerEngineTest.one(engine.tick());
        assertTrue(half.text(), half.text().matches("▶ (25|50) % — cut\\.nc, T2 rough: ~.*"));
        assertTrue(half.text(), half.text().endsWith(" (+1 tool change waiting on you)"));

        TriggerEngine.Notification tool = TriggerEngineTest.one(feed("event",
                "{\"type\":\"interceptor\",\"interceptor\":\"BitSetterInterceptor\",\"state\":\"waiting_for_user\","
                        + "\"trigger\":\"T21 M6\",\"file_line\":28,\"step\":\""
                        + com.willwinder.ugs.nbp.bridge.runtime.ToolChangeRoutine.STEP_CHANGE + "\"}"));
        assertEquals(Triggers.TOOL_CHANGE_WAITING, tool.trigger());
        assertTrue(tool.text(), tool.text().contains(". Confirm in UGS. After it: ~3 min left, done ≈ 17:"));
        assertTrue(tool.text(), tool.text().endsWith(" if you continue now."));

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(60)); // operator time: excluded
        feed("event", "{\"type\":\"interceptor\",\"state\":\"inactive\"}");
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(220));
        TriggerEngine.Notification done = TriggerEngineTest.one(feed("event", "{\"type\":\"stream\",\"event\":\"complete\"}"));
        assertTrue(done.text(), done.text().startsWith("✔ Job finished: cut.nc — 8 min, 49 lines; machine time 7 min, estimated 7 min (+"));
    }

    @Test
    public void everyMinutesWhenConfigured() {
        NotifyConfig.Builder b = NotifyConfig.defaults().toBuilder();
        b.progressEveryPercent = 0;
        b.progressEveryMinutes = 2;
        config.set(b.build());
        feed("event", "{\"type\":\"stream\",\"event\":\"started\"}");
        feed("file", "{\"action\":\"stream_start\",\"path\":\"" + file + "\",\"lines\":49}");
        cache.get(file, null, engine.eta().controller(), 10_000).orElseThrow();
        TriggerEngineTest.one(engine.tick()); // start
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(100));
        assertTrue(engine.tick().isEmpty());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));
        TriggerEngine.Notification n = TriggerEngineTest.one(engine.tick());
        assertTrue(n.text(), n.text().startsWith("▶ cut.nc, rough: ~"));
        assertTrue(engine.tick().isEmpty());
    }
}

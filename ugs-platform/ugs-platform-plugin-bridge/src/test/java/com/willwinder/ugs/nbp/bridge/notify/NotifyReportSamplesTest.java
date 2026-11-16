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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.log.LogFiles;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Not a check: writes real outputs of this code for the review report. Runs only with
 * {@code -Dnotify.report.dir=<dir>} (optionally {@code -Dnotify.report.job=<a real .nc>} for tool names).
 * Uses the loopback fake server only.
 */
public class NotifyReportSamplesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writeSamples() throws Exception {
        String dir = System.getProperty("notify.report.dir");
        Assume.assumeTrue(dir != null && !dir.isBlank());
        Path out = Paths.get(dir);
        Files.createDirectories(out);
        String jobProp = System.getProperty("notify.report.job");
        Path job;
        int lines;
        if (jobProp != null && Files.exists(Paths.get(jobProp))) {
            job = Paths.get(jobProp);
        } else {
            job = tmp.getRoot().toPath().resolve("two_tools_T02_T21.nc");
            Files.writeString(job, TriggerEngineTest.TWO_TOOLS);
        }
        lines = (int) Files.lines(job).count();
        int m6 = 0;
        List<String> all = Files.readAllLines(job);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).matches("(?i)^T21\\s*M0?6.*")) {
                m6 = i + 1;
                break;
            }
        }

        try (FakeChatServer server = new FakeChatServer()) {
            Path home = tmp.newFolder("home").toPath();
            SessionLog log = LogFiles.newLog(home, Clock.systemDefaultZone(), System::nanoTime, 10_000);
            log.start();
            NotifyConfig.Builder b = NotifyConfig.parse(NotifyConfigTest.ready(true, true)).toBuilder();
            b.progressEveryPercent = 25;
            b.rateLimitPerMinute = 60;
            b.build().save(home.resolve("notify.json"));
            AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(10_000));
            BridgeNotifier n = new BridgeNotifier(log, home.resolve("notify.json"), Sinks.newClient(), server.base(),
                    server.base(), nanos::get, new TriggerEngine.JobModelTools());
            n.reloadIfChanged();
            String path = job.toString().replace("\\", "/");
            String[] script = {
                    "session|0|{\"event\":\"connect\",\"port\":\"/dev/cu.usbmodem1101\"}",
                    "event|10|{\"type\":\"stream\",\"event\":\"started\"}",
                    "file|10|{\"action\":\"stream_start\",\"path\":\"" + path + "\",\"lines\":" + lines + "}",
                    "tx|910|{\"cmd_id\":\"u-1\",\"file_line\":" + (lines / 4 + 1) + "}",
                    "event|920|{\"type\":\"controller_state\",\"state\":\"hold\",\"previous\":\"run\"}",
                    "TICK|61",
                    "event|1000|{\"type\":\"controller_state\",\"state\":\"run\",\"previous\":\"hold\"}",
                    "tx|1840|{\"cmd_id\":\"u-2\",\"file_line\":" + (lines / 2 + 1) + "}",
                    "tx|1850|{\"line\":\"G2 X10 Y5 I3\",\"cmd_id\":\"u-3\",\"file_line\":" + (lines / 2 + 7) + "}",
                    "command|1851|{\"id\":\"u-3\",\"origin\":\"ugs\",\"line\":\"G2 X10 Y5 I3\",\"state\":\"error\","
                            + "\"error_code\":33,\"response\":[\"error:33\"]}",
                    "event|1852|{\"type\":\"stream\",\"event\":\"paused_on_error\"}",
                    "tx|2440|{\"cmd_id\":\"u-4\",\"file_line\":" + m6 + "}",
                    "event|2441|{\"type\":\"interceptor\",\"state\":\"waiting_for_user\",\"previous\":\"running\","
                            + "\"interceptor\":\"ToolChangeInterceptor\",\"trigger\":\"T21 M6\",\"file_line\":" + m6
                            + ",\"step\":\"toolChange.changeTool\",\"options\":[\"continue\",\"abort\"]}",
                    "event|2500|{\"type\":\"alarm\",\"code\":4,\"line\":\"ALARM:4\"}",
                    "tx|2760|{\"cmd_id\":\"u-5\",\"file_line\":" + (lines * 3 / 4 + 1) + "}",
                    "tx|3680|{\"cmd_id\":\"u-6\",\"file_line\":" + lines + "}",
                    "event|" + (10 + 61 * 60 + 12) + "|{\"type\":\"stream\",\"event\":\"complete\"}",
                    "session|3700|{\"event\":\"disconnect\",\"reason\":\"connection_closed\"}",
            };
            for (String step : script) {
                String[] p = step.split("\\|", 3);
                if (p[0].equals("TICK")) {
                    nanos.addAndGet(TimeUnit.SECONDS.toNanos(Long.parseLong(p[1])));
                    for (CompletableFuture<JsonObject> f : n.tickNow()) {
                        f.get(5, TimeUnit.SECONDS);
                    }
                    continue;
                }
                JsonObject rec = new JsonObject();
                rec.addProperty("kind", p[0]);
                rec.addProperty("mono", Double.parseDouble(p[1]));
                rec.add("data", JsonParser.parseString(p[2]).getAsJsonObject());
                for (CompletableFuture<JsonObject> f : n.handleNow(rec)) {
                    f.get(5, TimeUnit.SECONDS);
                }
                nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            }
            n.send(Triggers.CLAUDE_AUTHORITY_REQUEST,
                    Messages.authorityRequest("Motion", "Query", "probe the stock top at X20 Y20"), "warn");
            n.send(Triggers.CLAUDE_MESSAGE, "Facing pass done; the pad is flat to 0.05 mm. Starting the rough.", "info");
            n.send(Triggers.CLAUDE_MESSAGE, "Facing pass done; the pad is flat to 0.05 mm. Starting the rough.", "info");
            n.sendTest().get(5, TimeUnit.SECONDS);
            Thread.sleep(300);
            n.close();
            log.awaitWritten(3000);

            JsonArray reqs = new JsonArray();
            for (FakeChatServer.Request r : server.requests) {
                JsonObject o = new JsonObject();
                o.addProperty("method", r.method());
                o.addProperty("path", r.path().replace(NotifyConfigTest.TOKEN, "<bot_token>"));
                o.addProperty("content_type", r.contentType());
                o.add("body", JsonParser.parseString(r.body()));
                reqs.add(o);
            }
            JsonArray recs = new JsonArray();
            for (LogRecord r : log.since(0, Set.of(LogRecord.Kind.NOTIFY), 1000)) {
                recs.add(JsonParser.parseString(r.json()));
            }
            var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Files.writeString(out.resolve("requests.json"), gson.toJson(reqs));
            Files.writeString(out.resolve("notify-records.json"), gson.toJson(recs));
            Files.writeString(out.resolve("notify.json.saved"),
                    Files.readString(home.resolve("notify.json")).replace(NotifyConfigTest.TOKEN, "<bot_token>")
                            .replace(NotifyConfigTest.WEBHOOK, "<webhook_url>"));
            log.close();
        }
    }
}

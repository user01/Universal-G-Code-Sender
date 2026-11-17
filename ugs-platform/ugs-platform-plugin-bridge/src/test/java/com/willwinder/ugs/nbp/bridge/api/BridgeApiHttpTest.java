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
package com.willwinder.ugs.nbp.bridge.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The API over real HTTP and WebSocket on loopback, against {@link FakeLink}. */
public class BridgeApiHttpTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private ApiFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ApiFixture(tmp.newFolder("home").toPath());
    }

    @After
    public void tearDown() {
        f.close();
    }

    private static void assertError(HttpResponse<String> r, int status, String code) {
        assertEquals(r.body(), status, r.statusCode());
        assertEquals(r.body(), code, ApiFixture.json(r).get("error").getAsString());
    }

    private List<JsonObject> records(String kind) {
        return f.log.since(0, Set.of(kind), 100_000).stream()
                .map(r -> JsonParser.parseString(r.json()).getAsJsonObject()).toList();
    }

    // ------------------------------------------------------------------ controller report age

    @Test
    public void statusReportsTheAgeOfTheLastControllerReportAndASilentController() throws Exception {
        f.link.status();
        JsonObject r = ApiFixture.json(f.get("/status")).getAsJsonObject("controller_report");
        assertEquals(0.0, r.get("age_s").getAsDouble(), 1e-9);
        assertFalse(r.get("silent").getAsBoolean());
        assertTrue(r.get("seq").getAsLong() > 0);

        f.millis.addAndGet(6_000); // the controller freezes: UGS still shows it connected
        r = ApiFixture.json(f.get("/status")).getAsJsonObject("controller_report");
        assertEquals(6.0, r.get("age_s").getAsDouble(), 1e-9);
        assertTrue(r.get("silent").getAsBoolean());
        f.host.checkSilence();
        assertTrue("no event before 10 s", records("event").stream()
                .noneMatch(e -> e.getAsJsonObject("data").get("type").getAsString().equals("controller_silent")));

        f.millis.addAndGet(5_000);
        f.host.checkSilence();
        f.host.checkSilence(); // logged once
        List<JsonObject> silent = records("event").stream()
                .filter(e -> e.getAsJsonObject("data").get("type").getAsString().equals("controller_silent")).toList();
        assertEquals(1, silent.size());
        assertEquals(11.0, silent.get(0).getAsJsonObject("data").get("age_s").getAsDouble(), 1e-9);

        f.link.status(); // reports resume
        f.host.checkSilence();
        assertTrue(records("event").stream()
                .anyMatch(e -> e.getAsJsonObject("data").get("type").getAsString().equals("controller_responding")));
        assertFalse(ApiFixture.json(f.get("/status")).getAsJsonObject("controller_report").get("silent").getAsBoolean());
    }

    @Test
    public void noControllerReportWhenDisconnected() throws Exception {
        f.link.status();
        f.link.disconnect();
        assertTrue(ApiFixture.json(f.get("/status")).get("controller_report").isJsonNull());
    }

    // ------------------------------------------------------------------ access control

    @Test
    public void wrongOrMissingTokenIs401AndLogged() throws Exception {
        f.unlock(Level.QUERY);
        assertError(f.get("/status", null), 401, "unauthorized");
        assertError(f.get("/status", "nope"), 401, "unauthorized");
        assertError(f.get("/status", "v.nope"), 401, "unauthorized");
        assertTrue(records("client").stream().anyMatch(r -> r.getAsJsonObject("data").get("action").getAsString().equals("auth_failed")));
    }

    @Test
    public void lockedRefusesEveryMcpActionWith423ButReadsWork() throws Exception {
        for (String p : new String[]{"/status", "/log", "/settings", "/profile"}) {
            assertEquals(p, 200, f.get(p).statusCode());
        }
        assertTrue(ApiFixture.json(f.get("/status")).getAsJsonObject("authority").get("locked").getAsBoolean());
        for (String[] p : new String[][]{{"/realtime", "{\"op\":\"hold\"}"}, {"/command", "{\"lines\":[\"?\"]}"},
                {"/notify", "{\"text\":\"x\"}"}}) {
            HttpResponse<String> r = f.post(p[0], p[1]);
            assertError(r, 423, "locked_by_operator");
            JsonObject body = ApiFixture.json(r);
            assertEquals(Set.of("error", "message"), body.keySet());
            assertEquals("UGS access is locked by the operator", body.get("message").getAsString());
        }
        assertTrue("nothing reached the machine", f.link.realtime.isEmpty() && f.link.sent.isEmpty());
        assertTrue(records("client").stream().anyMatch(r -> r.getAsJsonObject("data").get("action").getAsString().equals("locked_refused")));
    }

    @Test
    public void viewerIsReadOnlyAndNotAffectedByTheLock() throws Exception {
        String vt = "v." + f.viewerToken();
        HttpResponse<String> s = f.get("/status", vt);
        assertEquals(200, s.statusCode());
        assertTrue(ApiFixture.json(s).getAsJsonObject("authority").get("locked").getAsBoolean());
        assertError(f.post("/realtime", "{\"op\":\"hold\"}", vt), 403, "authority_required");
        assertError(f.post("/command", "{\"lines\":[\"?\"]}", vt), 403, "authority_required");
        assertError(f.get("/status", f.viewerToken()), 401, "unauthorized"); // the viewer token without its v. class
    }

    @Test
    public void levelTooLowIs403AndNothingIsSent() throws Exception {
        f.unlock(Level.QUERY);
        HttpResponse<String> r = f.post("/command", "{\"lines\":[\"$G\",\"G91 G0 Z5\",\"G90\"]}");
        assertError(r, 403, "authority_required");
        assertEquals("motion", ApiFixture.json(r).get("required").getAsString());
        assertError(f.post("/file/run", "{}"), 403, "authority_required");
        f.authority.setLevel(Level.OBSERVE, "operator");
        assertError(f.post("/command", "{\"lines\":[\"$$\"]}"), 403, "authority_required");
        assertTrue(f.link.sent.isEmpty());
    }

    @Test
    public void badRequestsAre400() throws Exception {
        f.unlock(Level.MOTION);
        assertError(f.post("/command", "{\"lines\":[]}"), 400, "invalid_request");
        assertError(f.post("/command", "not json"), 400, "invalid_request");
        assertError(f.post("/command", "{\"lines\":[\"G0 X1 !\"]}"), 400, "invalid_request");
        assertError(f.post("/command", "{\"lines\":[\"G0\\nG1\"]}"), 400, "invalid_request");
        assertError(f.post("/realtime", "{\"op\":\"explode\"}"), 400, "invalid_request");
        assertError(f.get("/log?since=x"), 400, "invalid_request");
        assertError(f.get("/nope"), 404, "not_found");
    }

    @Test
    public void hostHeaderMustBeLoopback() throws Exception {
        f.unlock(Level.QUERY);
        try (Socket s = new Socket("127.0.0.1", f.host.port())) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/bridge/v1/status HTTP/1.1\r\nHost: evil.example:80\r\nAuthorization: Bearer " + f.token
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String resp = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(resp, resp.startsWith("HTTP/1.1 400"));
        }
    }

    // ------------------------------------------------------------------ read side

    @Test
    public void statusHasTheSchemaShape() throws Exception {
        f.unlock(Level.QUERY);
        JsonObject s = ApiFixture.json(f.get("/status"));
        for (String k : new String[]{"seq", "connection", "controller", "homed_this_connection", "alarm", "authority", "stream",
                "interceptor", "tool"}) {
            assertTrue(k, s.has(k));
        }
        assertTrue(s.getAsJsonObject("connection").get("connected").getAsBoolean());
        assertEquals("Idle", s.getAsJsonObject("controller").get("state").getAsString());
        assertFalse(s.get("homed_this_connection").getAsBoolean());
        assertEquals("query", s.getAsJsonObject("authority").get("level").getAsString());
        assertEquals("idle", s.getAsJsonObject("stream").get("state").getAsString());
        assertTrue(s.get("seq").getAsLong() >= f.log.lastSeq() - 5);
    }

    @Test
    public void logAndConsolePageWithSinceAndKinds() throws Exception {
        f.unlock(Level.QUERY);
        JsonObject all = ApiFixture.json(f.get("/log?since=0&limit=3"));
        JsonArray recs = all.getAsJsonArray("records");
        assertEquals(3, recs.size());
        assertEquals(recs.get(2).getAsJsonObject().get("seq").getAsLong(), all.get("next").getAsLong());
        JsonObject rest = ApiFixture.json(f.get("/log?since=" + all.get("next").getAsLong() + "&kinds=status,rx"));
        for (JsonElement e : rest.getAsJsonArray("records")) {
            String k = e.getAsJsonObject().get("kind").getAsString();
            assertTrue(k, k.equals("status") || k.equals("rx"));
        }
        JsonObject console = ApiFixture.json(f.get("/console?since=0&kinds=status"));
        for (JsonElement e : console.getAsJsonArray("records")) {
            assertTrue(Set.of("message", "tx", "rx", "command").contains(e.getAsJsonObject().get("kind").getAsString()));
        }
    }

    @Test
    public void settingsComeFromTheLastDollarDollar() throws Exception {
        f.unlock(Level.QUERY);
        assertEquals("", ApiFixture.json(f.get("/settings")).get("raw").getAsString());
        String id = ApiFixture.json(f.post("/command", "{\"lines\":[\"$$\"]}")).get("id").getAsString();
        f.awaitCommand(id);
        JsonObject s = ApiFixture.json(f.get("/settings"));
        assertTrue(s.get("raw").getAsString().contains("$130=445.000"));
        assertEquals(445.0, s.getAsJsonObject("settings").getAsJsonObject("$130").get("value").getAsDouble(), 1e-9);
        assertNotNull(s.get("read_at").getAsString());
    }

    @Test
    public void fileProfileOriginsAndJobState() throws Exception {
        f.unlock(Level.QUERY);
        assertError(f.get("/file"), 404, "not_found");
        assertError(f.get("/job/state"), 404, "not_found");
        JsonObject profile = ApiFixture.json(f.get("/profile"));
        assertEquals("ugs-bridge-profile/1", profile.get("format").getAsString());
        Files.writeString(f.home.resolve("profile.json"), "{\"format\":\"ugs-bridge-profile/1\",\"name\":\"override\"}");
        assertEquals("override", ApiFixture.json(f.get("/profile")).get("name").getAsString());
        JsonObject origins = ApiFixture.json(f.get("/origins"));
        assertEquals("ugs-bridge-origins/1", origins.get("format").getAsString());
        Path nc = tmp.newFile("job.nc").toPath();
        Files.writeString(nc, "G21\nG0 X1\n");
        f.link.file = nc.toFile();
        JsonObject file = ApiFixture.json(f.get("/file"));
        assertEquals(nc.toString(), file.get("path").getAsString());
        assertEquals(2, file.get("lines").getAsInt());
        HttpResponse<String> content = f.get("/file/content", "v." + f.viewerToken());
        assertEquals("G21\nG0 X1\n", content.body());
        assertTrue(content.headers().firstValue("content-type").orElse("").startsWith("text/plain"));
    }

    static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void fileEstimateJobStateEtaAndEtaRecords() throws Exception {
        assertError(f.get("/file/estimate"), 404, "not_found");
        Path nc = tmp.newFile("est.nc").toPath();
        Files.writeString(nc, "G21 G90\n(---- OP 01 | T2 | a | b ----)\nT2 M6\nG1 X100 F600\nG1 X0\nM2\n");
        f.link.file = nc.toFile();
        // read endpoint: works while Claude is locked
        JsonObject e = ApiFixture.json(f.get("/file/estimate"));
        assertEquals(1, e.get("version").getAsInt());
        assertEquals("grbl-planner", e.get("source").getAsString());
        assertEquals("profile", e.get("settings_from").getAsString());
        assertEquals(6, e.getAsJsonArray("line_end_s").size());
        assertEquals(1, e.getAsJsonArray("tool_changes").size());
        assertEquals("a", e.getAsJsonArray("ops").get(0).getAsJsonObject().get("name").getAsString());
        double machine = e.get("machine_s").getAsDouble();
        assertTrue("two 100 mm cuts at 10 mm/s: " + machine, machine > 20 && machine < 20.2);
        assertFalse(ApiFixture.json(f.get("/file/estimate?lines=0")).has("line_end_s"));
        // the controller's $$ wins over the profile
        for (String l : List.of("$110=5000.000", "$111=5000", "$112=500", "$120=100", "$121=100", "$122=50")) {
            f.log.append(LogRecord.Kind.RX, LogRecord.Src.CONTROLLER, obj("{\"line\":\"" + l + "\"}"));
        }
        JsonObject live = ApiFixture.json(f.get("/file/estimate"));
        assertEquals("controller", live.get("settings_from").getAsString());
        assertTrue(live.get("machine_s").getAsDouble() > machine);

        // a stream: /job/state gains eta, and eta event records are written
        f.log.append(LogRecord.Kind.EVENT, LogRecord.Src.OPERATOR, obj("{\"type\":\"stream\",\"event\":\"started\"}"));
        JsonObject start = new JsonObject();
        start.addProperty("action", "stream_start");
        start.addProperty("path", nc.toString());
        f.log.append(LogRecord.Kind.FILE, LogRecord.Src.OPERATOR, start);
        ApiFixture.await(() -> f.host.eta().estimate() != null, "estimate");
        JsonObject eta = ApiFixture.json(f.get("/job/state")).getAsJsonObject("eta");
        assertTrue(eta.get("active").getAsBoolean());
        assertEquals("est.nc", eta.get("file").getAsString());
        assertEquals("a", eta.get("current_op").getAsString());
        assertEquals(1, eta.get("tool_changes_remaining").getAsInt());
        f.host.etaEvents().tick();
        f.millis.addAndGet(31_000);
        f.host.etaEvents().tick();
        f.log.append(LogRecord.Kind.EVENT, LogRecord.Src.UGS, obj("{\"type\":\"stream\",\"event\":\"complete\"}"));
        f.host.etaEvents().tick();
        List<String> reasons = new ArrayList<>();
        for (LogRecord r : f.log.since(0, Set.of(LogRecord.Kind.EVENT), 10_000)) {
            JsonObject d = obj(r.json()).getAsJsonObject("data");
            if (d.has("type") && "eta".equals(d.get("type").getAsString())) {
                reasons.add(d.get("reason").getAsString());
            }
        }
        assertEquals(List.of("start", "tick", "done"), reasons);
    }

    @Test
    public void telegramAnswersFromTheApiStateAndPausesWhileLocked() throws Exception {
        TelegramAccess t = f.host.telegramAccess();
        assertTrue(f.authority.isLocked());
        String status = t.status();
        assertTrue(status, status.startsWith("State: Idle\nWork: X0.00 Y0.00 Z0.00\nMachine: X-10.00 Y-20.00 Z-5.00"));
        assertTrue(status, status.contains("Job: none running"));
        assertTrue(status, status.endsWith("Claude: locked out"));
        assertEquals("No job has run since UGS started.", t.eta());
        assertFalse(t.jobRunning());
        f.log.append(LogRecord.Kind.EVENT, LogRecord.Src.OPERATOR, obj("{\"type\":\"stream\",\"event\":\"started\"}"));
        f.log.append(LogRecord.Kind.EVENT, LogRecord.Src.CONTROLLER, obj("{\"type\":\"alarm\",\"code\":1}"));
        assertTrue(t.jobRunning());
        assertEquals("0x21", t.pause()); // the operator's action: Claude's lock doesn't apply
        assertEquals(List.of("hold"), f.link.realtime);
        String log = t.log(5);
        assertTrue(log, log.contains(" stream started") && log.contains(" ALARM 1"));
    }

    @Test
    public void viewerIsServedAtView() throws Exception {
        HttpResponse<String> r = f.http.send(java.net.http.HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + f.host.port() + "/view/")).build(), HttpResponse.BodyHandlers.ofString());
        if (StaticFiles.available()) {
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("<html") || r.body().contains("<!doctype") || r.body().contains("<!DOCTYPE"));
        } else {
            assertEquals(404, r.statusCode());
        }
        HttpResponse<String> esc = f.http.send(java.net.http.HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + f.host.port() + "/view/..%2F..%2Fapi%2FBridgeApi.class")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, esc.statusCode());
    }

    // ------------------------------------------------------------------ act side

    @Test
    public void queryLinesRunInOrderAndAreAttributedToClaude() throws Exception {
        f.unlock(Level.QUERY);
        HttpResponse<String> r = f.post("/command", "{\"lines\":[\"$G\",\"$#\",\"?\"],\"tag\":\"look\",\"expect\":\"state\"}");
        assertEquals(r.body(), 202, r.statusCode());
        JsonObject accepted = ApiFixture.json(r);
        String id = accepted.get("id").getAsString();
        assertTrue(id.matches("c-\\d+"));
        assertEquals("query", accepted.getAsJsonArray("lines").get(0).getAsJsonObject().get("class").getAsString());
        JsonObject res = f.awaitCommand(id);
        assertEquals("done", res.get("state").getAsString());
        assertEquals("look", res.get("tag").getAsString());
        assertEquals(List.of("$G", "$#"), f.link.sent);
        JsonArray lines = res.getAsJsonArray("lines");
        assertTrue(lines.get(1).getAsJsonObject().getAsJsonArray("response").toString().contains("G54"));
        assertTrue(lines.get(2).getAsJsonObject().getAsJsonArray("response").get(0).getAsString().startsWith("<Idle"));
        assertTrue(f.link.realtime.contains("status"));
        List<JsonObject> cmds = records("command");
        assertTrue(cmds.stream().allMatch(c -> !c.getAsJsonObject("data").get("id").getAsString().startsWith(id + "-")
                || c.get("src").getAsString().equals("claude")));
        assertTrue(cmds.stream().anyMatch(c -> c.getAsJsonObject("data").get("id").getAsString().equals(id + "-0")
                && c.getAsJsonObject("data").get("state").getAsString().equals("queued")));
    }

    @Test
    public void anErrorStopsTheRequestAndDropsTheRest() throws Exception {
        f.unlock(Level.MOTION);
        String id = ApiFixture.json(f.post("/command", "{\"lines\":[\"G0 X1\",\"G999\",\"G0 X2\"]}")).get("id").getAsString();
        JsonObject res = f.awaitCommand(id);
        assertEquals("failed", res.get("state").getAsString());
        JsonArray l = res.getAsJsonArray("lines");
        assertEquals("ok", l.get(0).getAsJsonObject().get("state").getAsString());
        assertEquals("error", l.get(1).getAsJsonObject().get("state").getAsString());
        assertEquals(20, l.get(1).getAsJsonObject().get("error_code").getAsInt());
        assertEquals("dropped", l.get(2).getAsJsonObject().get("state").getAsString());
        assertEquals(List.of("G0 X1", "G999"), f.link.sent);
    }

    @Test
    public void homingWaitsForTheOperatorAndCountsAsHomed() throws Exception {
        f.unlock(Level.MOTION);
        f.prompts.answer = null; // leave the dialog open
        String id = ApiFixture.json(f.post("/command", "{\"lines\":[\"$H\",\"G0 X1\"]}")).get("id").getAsString();
        JsonObject waiting = f.awaitCommand(id, "awaiting_operator");
        assertEquals("awaiting_operator", waiting.getAsJsonArray("lines").get(0).getAsJsonObject().get("state").getAsString());
        assertTrue(f.link.sent.isEmpty());
        assertTrue(f.prompts.asked.get(0).contains("$H"));
        f.prompts.answerOpen(true);
        JsonObject done = f.awaitCommand(id);
        assertEquals("done", done.get("state").getAsString());
        assertEquals(List.of("$H", "G0 X1"), f.link.sent);
        assertTrue(ApiFixture.json(f.get("/status")).get("homed_this_connection").getAsBoolean());
    }

    @Test
    public void declinedUnlockDropsTheRequest() throws Exception {
        f.unlock(Level.MOTION);
        f.prompts.answer = false;
        String id = ApiFixture.json(f.post("/command", "{\"lines\":[\"$X\",\"G0 X1\"]}")).get("id").getAsString();
        JsonObject res = f.awaitCommand(id);
        assertEquals("dropped", res.get("state").getAsString());
        assertTrue(res.get("reason").getAsString().contains("declined"));
        assertTrue(f.link.sent.isEmpty());
    }

    @Test
    public void lockingDropsQueuedClaudeCommandsAndClosesTheDialog() throws Exception {
        f.unlock(Level.MOTION);
        f.link.hold.add("G0 X1");
        f.prompts.answer = null;
        String a = ApiFixture.json(f.post("/command", "{\"lines\":[\"G0 X1\",\"G0 X2\"]}")).get("id").getAsString();
        String b = ApiFixture.json(f.post("/command", "{\"lines\":[\"$H\"]}")).get("id").getAsString();
        ApiFixture.await(() -> f.link.sent.contains("G0 X1"), "first line sent");
        f.lock.lock();
        f.link.release();
        f.unlock(Level.QUERY);
        JsonObject ra = f.awaitCommand(a);
        assertEquals("ok", ra.getAsJsonArray("lines").get(0).getAsJsonObject().get("state").getAsString());
        assertEquals("dropped", ra.getAsJsonArray("lines").get(1).getAsJsonObject().get("state").getAsString());
        assertEquals("dropped", f.awaitCommand(b).get("state").getAsString());
        assertEquals("sent lines are never recalled; unsent ones never go", List.of("G0 X1"), f.link.sent);
        assertTrue(records("command").stream().anyMatch(c -> c.getAsJsonObject("data").get("state").getAsString().equals("dropped")
                && c.getAsJsonObject("data").get("reason").getAsString().contains("locked")));
    }

    @Test
    public void anAlarmDisarmsMotionAndDropsWhatIsQueued() throws Exception {
        f.unlock(Level.MOTION);
        String id = ApiFixture.json(f.post("/command", "{\"lines\":[\"G38.2 Z-99 F100\",\"G0 Z5\"]}")).get("id").getAsString();
        JsonObject res = f.awaitCommand(id);
        assertEquals("failed", res.get("state").getAsString());
        assertEquals(5, res.getAsJsonArray("lines").get(0).getAsJsonObject().get("alarm_code").getAsInt());
        ApiFixture.await(() -> f.authority.level() == Level.QUERY, "disarm on alarm");
        JsonObject st = ApiFixture.json(f.get("/status"));
        assertEquals(5, st.getAsJsonObject("alarm").get("code").getAsInt());
    }

    @Test
    public void realtimeIsAlwaysAllowedWhileUnlockedAndResetDisarms() throws Exception {
        f.unlock(Level.OBSERVE);
        for (String op : new String[]{"hold", "resume", "jog_cancel", "status"}) {
            HttpResponse<String> r = f.post("/realtime", "{\"op\":\"" + op + "\"}");
            assertEquals(r.body(), 200, r.statusCode());
        }
        assertEquals("0x21", ApiFixture.json(f.post("/realtime", "{\"op\":\"hold\"}")).get("sent").getAsString());
        f.authority.setLevel(Level.MOTION, "operator");
        assertEquals("0x18", ApiFixture.json(f.post("/realtime", "{\"op\":\"reset\"}")).get("sent").getAsString());
        assertEquals(Level.OBSERVE, f.authority.level());
        f.link.connected = false;
        assertError(f.post("/realtime", "{\"op\":\"hold\"}"), 409, "not_connected");
        assertError(f.post("/command", "{\"lines\":[\"$G\"]}"), 403, "authority_required"); // level first, like the fake
        f.authority.setLevel(Level.QUERY, "operator");
        assertError(f.post("/command", "{\"lines\":[\"$G\"]}"), 409, "not_connected");
    }

    @Test
    public void fileLoadAndRunNeedTheirLevelsAndTheOperator() throws Exception {
        Path nc = tmp.newFile("part.nc").toPath();
        Files.writeString(nc, "G21\nG0 X1\nM2\n");
        f.unlock(Level.MOTION);
        HttpResponse<String> load = f.post("/file/load", "{\"path\":\"" + nc + "\"}");
        assertEquals(load.body(), 200, load.statusCode());
        assertEquals(3, ApiFixture.json(load).get("lines").getAsInt());
        assertError(f.post("/file/load", "{\"path\":\"relative.nc\"}"), 400, "invalid_request");
        assertError(f.post("/file/run", "{}"), 403, "authority_required");
        f.authority.setLevel(Level.STREAM, "operator");
        f.prompts.answer = null;
        HttpResponse<String> run = f.post("/file/run", "{}");
        assertEquals(run.body(), 202, run.statusCode());
        assertEquals("awaiting_operator", ApiFixture.json(run).get("state").getAsString());
        ApiFixture.await(() -> !f.prompts.asked.isEmpty(), "dialog");
        String sha = ApiFixture.json(run).get("sha256").getAsString();
        assertTrue("dialog shows name and SHA-256", f.prompts.asked.get(0).contains("part.nc") && f.prompts.asked.get(0).contains(sha));
        assertError(f.post("/file/run", "{}"), 409, "busy");
        f.prompts.answerOpen(true);
        ApiFixture.await(() -> "claude".equals(f.link.streamedBy), "stream started for claude");
    }

    @Test
    public void notifyAndAuthorityRequest() throws Exception {
        f.unlock(Level.OBSERVE);
        assertEquals(202, f.post("/notify", "{\"text\":\"probe done\",\"level\":\"info\"}").statusCode());
        assertError(f.post("/notify", "{\"text\":\"\",\"level\":\"info\"}"), 400, "invalid_request");
        assertEquals(202, f.post("/authority/request", "{\"level\":\"motion\",\"reason\":\"probe Z\"}").statusCode());
        assertError(f.post("/authority/request", "{\"level\":\"god\",\"reason\":\"x\"}"), 400, "invalid_request");
        assertEquals(Level.OBSERVE, f.authority.level());
        assertEquals("motion", ApiFixture.json(f.get("/status")).getAsJsonObject("authority").get("requested").getAsString());
        assertEquals(2, f.notified.size());
        assertEquals("claude_message", f.notified.get(0)[0]);
        assertEquals("claude_authority_request", f.notified.get(1)[0]);
        assertTrue(f.notified.get(1)[1].contains("Motion") && f.notified.get(1)[1].contains("probe Z"));
    }

    @Test
    public void originsApplyWritesG10AndVerifiesWithDollarHash() throws Exception {
        Files.writeString(f.home.resolve("origins.json"), "{\"format\":\"ugs-bridge-origins/1\",\"origins\":[{\"id\":\"o-1\","
                + "\"wcs\":\"G54\",\"wco\":[-100,-200,-30],\"method\":\"manual\",\"created\":\"2026-11-15T12:00:00Z\"}]}");
        f.unlock(Level.QUERY);
        assertError(f.post("/origins/apply", "{\"origin_id\":\"o-1\"}"), 403, "authority_required");
        f.authority.setLevel(Level.MOTION, "operator");
        assertError(f.post("/origins/apply", "{\"origin_id\":\"nope\"}"), 404, "not_found");
        HttpResponse<String> r = f.post("/origins/apply", "{\"origin_id\":\"o-1\"}");
        assertEquals(r.body(), 202, r.statusCode());
        f.awaitCommand(ApiFixture.json(r).get("command_id").getAsString());
        assertEquals(List.of("G10 L2 P1 X-100.000 Y-200.000 Z-30.000", "$#"), f.link.sent);
        ApiFixture.await(() -> !records("origin").isEmpty(), "origin record");
        JsonObject o = records("origin").get(0).getAsJsonObject("data");
        assertTrue(o.get("verified").getAsBoolean());
        assertEquals("restored", o.get("method").getAsString());
    }

    @Test
    public void resumePlanIsComputedAndResumeIsNotYetImplemented() throws Exception {
        Path nc = tmp.newFile("resume.nc").toPath();
        Files.writeString(nc, "(---- OP 01 | T1 | face | ----)\nG21 G90\nT1 M6\nS10000 M3\nG0 Z5\nG0 X0 Y0\nG1 Z-1 F300\nG1 X10 F800\nG1 Y10\nG0 Z5\nM5\nM2\n");
        f.link.file = nc.toFile();
        f.unlock(Level.OBSERVE);
        HttpResponse<String> r = f.post("/job/resume/plan", "{\"target\":{\"line\":8}}");
        assertEquals(r.body(), 200, r.statusCode());
        JsonObject plan = ApiFixture.json(r);
        assertTrue(plan.has("plan_id"));
        assertTrue(f.link.sent.isEmpty());
        assertError(f.post("/job/resume/plan", "{\"target\":{}}"), 400, "invalid_request");
        assertError(f.post("/job/resume", "{\"plan_id\":\"" + plan.get("plan_id").getAsString() + "\"}"), 403, "authority_required");
        f.authority.setLevel(Level.STREAM, "operator");
        assertError(f.post("/job/resume", "{\"plan_id\":\"" + plan.get("plan_id").getAsString() + "\"}"), 501, "not_implemented");
    }

    // ------------------------------------------------------------------ WebSocket

    private static final class Collector implements WebSocket.Listener {
        final List<JsonObject> records = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                records.add(JsonParser.parseString(partial.toString()).getAsJsonObject());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.completeExceptionally(error);
        }
    }

    private WebSocket ws(String query, String auth, Collector c) throws Exception {
        WebSocket.Builder b = f.http.newWebSocketBuilder();
        if (auth != null) {
            b.header("Authorization", "Bearer " + auth);
        }
        return b.buildAsync(URI.create("ws://127.0.0.1:" + f.host.port() + BridgeApi.BASE + "/events" + query), c)
                .get(5, TimeUnit.SECONDS);
    }

    private static int handshakeStatus(Throwable e) {
        Throwable t = e instanceof ExecutionException ? e.getCause() : e;
        if (t instanceof java.net.http.WebSocketHandshakeException h) {
            return h.getResponse().statusCode();
        }
        throw new AssertionError("not a handshake failure", e);
    }

    @Test
    public void eventsSendTheBacklogThenLiveRecordsAndStayOpenOnLock() throws Exception {
        f.unlock(Level.QUERY);
        Collector c = new Collector();
        ws("?since=0", f.token, c);
        ApiFixture.await(() -> c.records.size() >= 3, "backlog");
        assertEquals(1, c.records.get(0).get("seq").getAsLong());
        long before = c.records.size();
        f.link.status();
        ApiFixture.await(() -> c.records.stream().anyMatch(r -> r.get("kind").getAsString().equals("client")), "live client record");
        ApiFixture.await(() -> c.records.size() > before, "live record");
        for (int i = 1; i < c.records.size(); i++) {
            assertTrue("strictly increasing seq", c.records.get(i).get("seq").getAsLong() > c.records.get(i - 1).get("seq").getAsLong());
        }
        f.lock.lock();
        ApiFixture.await(() -> c.records.stream().anyMatch(r -> r.get("kind").getAsString().equals("authority")
                && r.getAsJsonObject("data").has("locked") && r.getAsJsonObject("data").get("locked").getAsBoolean()), "lock record");
        assertFalse("the MCP stream stays open when locked", c.closed.isDone());
    }

    @Test
    public void eventsUpgradeWorksWhenLockedButNotUnauthorized() throws Exception {
        Collector locked = new Collector();
        ws("?since=0", f.token, locked);
        ApiFixture.await(() -> !locked.records.isEmpty(), "backlog while locked");
        try {
            ws("", "wrong", new Collector());
            fail("bad token");
        } catch (ExecutionException e) {
            assertEquals(401, handshakeStatus(e));
        }
        try {
            ws("?vt=" + f.token, null, new Collector());
            fail("the MCP token is never accepted as vt");
        } catch (ExecutionException e) {
            assertEquals(401, handshakeStatus(e));
        }
    }

    @Test
    public void viewerEventsWorkWithVtWhileLockedAndKindsFilter() throws Exception {
        Collector c = new Collector();
        ws("?since=0&kinds=status&vt=" + f.viewerToken(), null, c);
        ApiFixture.await(() -> !c.records.isEmpty(), "viewer backlog");
        f.lock.unlock();
        f.lock.lock();
        f.link.status();
        ApiFixture.await(() -> c.records.size() >= 2, "live status");
        assertTrue(c.records.stream().allMatch(r -> r.get("kind").getAsString().equals("status")));
        assertFalse("viewer stays open when locked", c.closed.isDone());
    }

    @Test
    public void viewerSubprotocolAuthIsEchoed() throws Exception {
        Collector c = new Collector();
        WebSocket w = f.http.newWebSocketBuilder().subprotocols("ugs-bridge.v1", "vt." + f.viewerToken())
                .buildAsync(URI.create("ws://127.0.0.1:" + f.host.port() + BridgeApi.BASE + "/events?since=" + f.log.lastSeq()), c)
                .get(5, TimeUnit.SECONDS);
        assertEquals("ugs-bridge.v1", w.getSubprotocol());
        w.sendClose(1000, "bye").get(5, TimeUnit.SECONDS);
        ApiFixture.await(() -> records(LogRecord.Kind.CLIENT).stream().anyMatch(
                r -> r.getAsJsonObject("data").get("action").getAsString().equals("disconnect")), "disconnect record");
    }

    @Test
    public void rawSocketHandshakeMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", com.willwinder.ugs.nbp.bridge.api.http.LiteHttpServerAccess.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }
}

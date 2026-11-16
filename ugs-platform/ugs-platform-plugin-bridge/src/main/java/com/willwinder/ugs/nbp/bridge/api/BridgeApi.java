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
import com.google.gson.reflect.TypeToken;
import com.willwinder.ugs.nbp.bridge.api.http.HttpRequest;
import com.willwinder.ugs.nbp.bridge.api.http.HttpResponse;
import com.willwinder.ugs.nbp.bridge.api.http.LiteHttpServer;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobState;
import com.willwinder.ugs.nbp.bridge.job.Json;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.job.ResumeContext;
import com.willwinder.ugs.nbp.bridge.job.ResumePlan;
import com.willwinder.ugs.nbp.bridge.job.ResumePlanner;
import com.willwinder.ugs.nbp.bridge.job.ResumeTarget;
import com.willwinder.ugs.nbp.bridge.log.FileFingerprint;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.ugs.nbp.bridge.notify.Notifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bridge API (bridge/docs/api.md, contract v1): routing, authentication, the MCP lock and every endpoint.
 * Transport-independent: {@link LiteHttpServer} hands it parsed requests, and tests call {@link #handle} directly.
 * <p>
 * Clients: {@code mcp} ({@code Authorization: Bearer <token file>}) is subject to the operator's lock;
 * {@code viewer} ({@code Bearer v.<viewer token>}, or {@code ?vt=}/subprotocol {@code vt.<token>} on the WebSocket)
 * is read-only and not affected by the lock.
 */
public final class BridgeApi implements LiteHttpServer.Handler, LiteHttpServer.UpgradeHandler {
    public static final String BASE = "/api/bridge/v1";
    public static final String VIEW = "/view/";
    static final String SUBPROTOCOL = "ugs-bridge.v1";
    private static final Set<String> CONSOLE_KINDS = Set.of("message", "tx", "rx", "command");
    private static final Set<String> REALTIME_OPS = Set.of("hold", "resume", "jog_cancel", "reset", "status");
    private static final Pattern G54 = Pattern.compile("\\[(G5[4-9]):\\s*(-?[\\d.]+),\\s*(-?[\\d.]+),\\s*(-?[\\d.]+)");
    private static final long CLIENT_LOG_INTERVAL_MS = 5000;

    enum Client { MCP, VIEWER }

    /** Everything the API reads from or acts on. */
    public record Deps(SessionLog log, Authority authority, StateTracker state, CommandService commands,
                       MachineLink link, OperatorPrompts prompts, ProfileSource profile, Path home,
                       Supplier<Notifier> notifier, Supplier<Optional<Path>> journal) {
    }

    private final Deps d;
    private final String mcpToken;
    private final String viewerToken;
    private final Set<EventStream> streams = EventStream.newRegistry();
    private final Map<String, Long> clientLogTimes = new ConcurrentHashMap<>();
    private final Map<String, ResumePlan> plans = new ConcurrentHashMap<>();
    private final AtomicInteger runCounter = new AtomicInteger();
    private volatile OperatorPrompts.Prompt runPrompt;
    private volatile int port;

    public BridgeApi(Deps deps, String mcpToken, String viewerToken) {
        this.d = deps;
        this.mcpToken = mcpToken;
        this.viewerToken = viewerToken;
        d.authority().addListener(this::onAuthority);
    }

    /** The bound port, for the Host/Origin checks (0 = don't check). */
    public void setPort(int port) {
        this.port = port;
    }

    public String viewerToken() {
        return viewerToken;
    }

    /** Open {@code WS /events} connections per client type. */
    public int openStreams(Client client) {
        return (int) streams.stream().filter(s -> s.client().equals(client.name().toLowerCase(Locale.ROOT))).count();
    }

    private void onAuthority(Authority.Change change, Authority.Snapshot s) {
        if (change == Authority.Change.LOCKED) {
            d.commands().dropAll("locked by the operator");
            OperatorPrompts.Prompt p = runPrompt;
            if (p != null) {
                p.cancel();
            }
            for (EventStream es : List.copyOf(streams)) {
                if (es.client().equals("mcp")) {
                    es.close(4423, "locked_by_operator");
                }
            }
        } else if (change == Authority.Change.DISARMED) {
            d.commands().dropAbove(s.level(), "authority dropped to " + s.level().id());
            OperatorPrompts.Prompt p = runPrompt;
            if (p != null && !s.level().allows(Level.STREAM)) {
                p.cancel();
            }
        } else if (change == Authority.Change.LEVEL) {
            d.commands().dropAbove(s.level(), "authority set to " + s.level().id());
        }
    }

    /** Closes every open event stream (shutdown). */
    public void closeAll() {
        for (EventStream es : List.copyOf(streams)) {
            es.close(1001, "bridge stopping");
        }
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public HttpResponse handle(HttpRequest req) {
        if (!hostOk(req)) {
            return ApiErrors.badRequest("bad Host header");
        }
        String path = req.path();
        if (path.equals("/view")) {
            return HttpResponse.text(302, "text/plain", "").withHeader("Location", VIEW);
        }
        if (path.startsWith(VIEW)) {
            if (!req.method().equals("GET") && !req.method().equals("HEAD")) {
                return ApiErrors.error(405, "invalid_request", "GET only");
            }
            return StaticFiles.serve(path.substring(VIEW.length()));
        }
        if (!path.startsWith(BASE + "/")) {
            return ApiErrors.notFound("no such endpoint");
        }
        String route = path.substring(BASE.length());
        Client client = authenticate(req.header("authorization"));
        if (client == null) {
            logClient(guessClient(req.header("authorization")), "auth_failed", req.remote(), route);
            return ApiErrors.unauthorized();
        }
        if (client == Client.MCP && d.authority().isLocked()) {
            logClient("mcp", "locked_refused", req.remote(), route);
            return ApiErrors.locked();
        }
        try {
            if (req.method().equals("GET")) {
                return get(route, req);
            }
            if (req.method().equals("POST")) {
                if (client == Client.VIEWER) {
                    return ApiErrors.error(403, "authority_required", "the viewer token is read-only");
                }
                JsonObject body = parseBody(req);
                if (body == null) {
                    return ApiErrors.badRequest("body must be a JSON object");
                }
                return post(route, body);
            }
            return ApiErrors.error(405, "invalid_request", "use GET or POST");
        } catch (BadRequest e) {
            return ApiErrors.badRequest(e.getMessage());
        }
    }

    private HttpResponse get(String route, HttpRequest req) {
        if (route.startsWith("/command/")) {
            CommandService.Req r = d.commands().find(route.substring("/command/".length()));
            return r == null ? ApiErrors.notFound("no command " + route.substring(9)) : ok(CommandService.toJson(r));
        }
        return switch (route) {
            case "/status" -> ok(status());
            case "/log" -> records(req, null);
            case "/console" -> records(req, CONSOLE_KINDS);
            case "/settings" -> ok(d.state().settingsJson());
            case "/file" -> {
                JsonObject f = fileInfo();
                yield f == null ? ApiErrors.notFound("no file loaded") : ok(f);
            }
            case "/file/content" -> fileContent();
            case "/profile" -> {
                String p = d.profile().json();
                yield p == null ? ApiErrors.notFound("no machine profile") : HttpResponse.json(200, p);
            }
            case "/origins" -> origins();
            case "/job/state" -> jobState();
            case "/events" -> ApiErrors.error(400, "invalid_request", "WS /events needs a WebSocket upgrade");
            default -> ApiErrors.notFound("no such endpoint: GET " + route);
        };
    }

    private HttpResponse post(String route, JsonObject body) {
        return switch (route) {
            case "/command" -> command(body);
            case "/realtime" -> realtime(body);
            case "/file/load" -> fileLoad(body);
            case "/file/run" -> fileRun();
            case "/notify" -> notify(body);
            case "/authority/request" -> authorityRequest(body);
            case "/origins/apply" -> originsApply(body);
            case "/job/resume/plan" -> resumePlan(body);
            case "/job/resume" -> resume(body);
            default -> ApiErrors.notFound("no such endpoint: POST " + route);
        };
    }

    // ------------------------------------------------------------------ auth

    Client authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        if (token.startsWith("v.") && constantTimeEquals(token.substring(2), viewerToken)) {
            return Client.VIEWER;
        }
        return constantTimeEquals(token, mcpToken) ? Client.MCP : null;
    }

    private static String guessClient(String header) {
        return header != null && header.startsWith("Bearer v.") ? "viewer" : "mcp";
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hostOk(HttpRequest req) {
        int p = port;
        if (p <= 0) {
            return true;
        }
        String host = req.header("host");
        return host == null || host.equals("127.0.0.1:" + p) || host.equals("localhost:" + p);
    }

    private boolean originOk(HttpRequest req) {
        String origin = req.header("origin");
        int p = port;
        return origin == null || p <= 0 || origin.equals("http://127.0.0.1:" + p) || origin.equals("http://localhost:" + p);
    }

    /** Logs a {@code client} record, at most once per {@value #CLIENT_LOG_INTERVAL_MS} ms per client and action. */
    private void logClient(String client, String action, String remote, String route) {
        String key = client + "|" + action;
        long now = System.currentTimeMillis();
        Long last = clientLogTimes.get(key);
        if (last != null && now - last < CLIENT_LOG_INTERVAL_MS) {
            return;
        }
        clientLogTimes.put(key, now);
        JsonObject data = new JsonObject();
        data.addProperty("client", client);
        data.addProperty("action", action);
        data.addProperty("remote", remote);
        data.addProperty("endpoint", route);
        d.log().append(LogRecord.Kind.CLIENT, LogRecord.Src.BRIDGE, data);
    }

    // ------------------------------------------------------------------ WebSocket

    @Override
    public LiteHttpServer.Upgrade upgrade(HttpRequest req) {
        if (!hostOk(req) || !originOk(req)) {
            return LiteHttpServer.Upgrade.deny(ApiErrors.error(403, "forbidden", "bad Host or Origin"));
        }
        if (!req.path().equals(BASE + "/events")) {
            return LiteHttpServer.Upgrade.deny(ApiErrors.notFound("no WebSocket endpoint " + req.path()));
        }
        List<String> offered = new ArrayList<>();
        String protoHeader = req.header("sec-websocket-protocol");
        if (protoHeader != null) {
            for (String p : protoHeader.split(",")) {
                if (!p.isBlank()) {
                    offered.add(p.trim());
                }
            }
        }
        Client client = authenticate(req.header("authorization"));
        String chosen = offered.contains(SUBPROTOCOL) ? SUBPROTOCOL : null;
        if (client == null) {
            // Browsers can't set headers on a WebSocket: the viewer token (only that one) may come in the URL or
            // as a subprotocol.
            String vt = req.param("vt");
            for (String p : offered) {
                if (p.startsWith("vt.")) {
                    vt = p.substring(3);
                    if (chosen == null) {
                        chosen = p;
                    }
                }
            }
            if (vt != null && vt.startsWith("v.")) {
                vt = vt.substring(2);
            }
            if (vt != null && constantTimeEquals(vt, viewerToken)) {
                client = Client.VIEWER;
            }
        }
        if (client == null) {
            logClient(guessClient(req.header("authorization")), "auth_failed", req.remote(), "/events");
            return LiteHttpServer.Upgrade.deny(ApiErrors.unauthorized());
        }
        if (client == Client.MCP && d.authority().isLocked()) {
            logClient("mcp", "locked_refused", req.remote(), "/events");
            return LiteHttpServer.Upgrade.deny(ApiErrors.locked());
        }
        long since;
        try {
            since = req.param("since") == null ? 0 : Long.parseLong(req.param("since"));
        } catch (NumberFormatException e) {
            return LiteHttpServer.Upgrade.deny(ApiErrors.badRequest("since must be an integer"));
        }
        Set<String> kinds = kinds(req.param("kinds"));
        EventStream es = new EventStream(d.log(), client.name().toLowerCase(Locale.ROOT), since, kinds, streams);
        return LiteHttpServer.Upgrade.accept(es, chosen);
    }

    // ------------------------------------------------------------------ read endpoints

    JsonObject status() {
        JsonObject s = new JsonObject();
        s.addProperty("seq", d.log().lastSeq());
        JsonObject tmp = new JsonObject();
        d.state().fillStatus(tmp);
        s.add("connection", tmp.get("connection"));
        s.add("controller", tmp.get("controller"));
        s.add("homed_this_connection", tmp.get("homed_this_connection"));
        s.add("alarm", tmp.get("alarm"));
        Authority.Snapshot a = d.authority().snapshot();
        JsonObject auth = new JsonObject();
        auth.addProperty("locked", a.locked());
        auth.addProperty("level", a.level().id());
        auth.addProperty("motion_expires_at", a.motionExpiresAt() == null ? null
                : a.motionExpiresAt().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        if (a.pendingRequest() != null) {
            auth.addProperty("requested", a.pendingRequest().level().id());
        }
        s.add("authority", auth);
        s.add("stream", tmp.get("stream"));
        s.add("interceptor", tmp.get("interceptor"));
        s.add("tool", tmp.get("tool"));
        return s;
    }

    private HttpResponse records(HttpRequest req, Set<String> fixedKinds) {
        long since;
        int limit;
        try {
            since = req.param("since") == null ? 0 : Long.parseLong(req.param("since"));
            limit = req.param("limit") == null ? 1000 : Integer.parseInt(req.param("limit"));
        } catch (NumberFormatException e) {
            return ApiErrors.badRequest("since and limit must be integers");
        }
        limit = Math.max(1, Math.min(limit, 5000));
        Set<String> kinds = fixedKinds != null ? fixedKinds : kinds(req.param("kinds"));
        long top = d.log().lastSeq();
        List<LogRecord> recs = d.log().since(since, kinds, limit);
        long next = Math.max(since, top);
        if (!recs.isEmpty()) {
            long lastReturned = recs.get(recs.size() - 1).seq();
            next = recs.size() >= limit ? lastReturned : Math.max(next, lastReturned);
        }
        StringBuilder sb = new StringBuilder("{\"records\":[");
        for (int i = 0; i < recs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(recs.get(i).json());
        }
        sb.append("],\"next\":").append(next);
        long oldest = d.log().oldestSeqInRing();
        if (oldest > since + 1) {
            sb.append(",\"truncated\":true,\"oldest_seq\":").append(oldest);
        }
        sb.append('}');
        return HttpResponse.json(200, sb.toString());
    }

    private static Set<String> kinds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> s = new HashSet<>();
        for (String k : raw.split(",")) {
            if (!k.isBlank()) {
                s.add(k.trim());
            }
        }
        return s.isEmpty() ? null : s;
    }

    private Path loadedPath() {
        JsonObject f = d.state().file();
        if (f != null && f.has("path") && f.get("path").isJsonPrimitive()) {
            return Path.of(f.get("path").getAsString());
        }
        File lf = d.link().loadedFile();
        return lf == null ? null : lf.toPath();
    }

    JsonObject fileInfo() {
        JsonObject f = d.state().file();
        if (f != null) {
            return f;
        }
        File lf = d.link().loadedFile();
        if (lf == null || !lf.isFile()) {
            return null;
        }
        try {
            FileFingerprint fp = FileFingerprint.of(lf.toPath());
            JsonObject o = new JsonObject();
            o.addProperty("path", fp.path().toString());
            o.addProperty("sha256", fp.sha256());
            o.addProperty("lines", fp.lines());
            o.add("loaded_at", com.google.gson.JsonNull.INSTANCE);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private HttpResponse fileContent() {
        Path p = loadedPath();
        if (p == null || !Files.isRegularFile(p)) {
            return ApiErrors.notFound("no file loaded");
        }
        try {
            byte[] b = Files.readAllBytes(p);
            return HttpResponse.bytes(200, "text/plain; charset=utf-8", b)
                    .withHeader("X-Bridge-File", p.getFileName().toString())
                    .withHeader("X-Bridge-SHA256", com.willwinder.ugs.nbp.bridge.job.Json.sha256Hex(b));
        } catch (IOException e) {
            return ApiErrors.error(500, "internal", "could not read the loaded file");
        }
    }

    private Path originsPath() {
        return d.home().resolve("origins.json");
    }

    private OriginStore originStore() throws IOException {
        Path p = originsPath();
        return Files.isRegularFile(p) ? OriginStore.read(p) : new OriginStore();
    }

    private HttpResponse origins() {
        try {
            return HttpResponse.json(200, Json.toJson(originStore()));
        } catch (IOException | RuntimeException e) {
            return ApiErrors.error(500, "internal", "origins.json is unreadable: " + e.getMessage());
        }
    }

    private HttpResponse jobState() {
        Optional<Path> j = d.journal().get();
        if (j.isEmpty()) {
            return ApiErrors.notFound("no job journal");
        }
        try {
            return HttpResponse.json(200, Files.readString(j.get()));
        } catch (IOException e) {
            return ApiErrors.notFound("no job journal");
        }
    }

    // ------------------------------------------------------------------ act endpoints

    private HttpResponse requireLevel(Level required, String what) {
        Level have = d.authority().level();
        if (!have.allows(required)) {
            return ApiErrors.forbidden(required, what + " needs " + required.id() + " authority (now " + have.id() + ")");
        }
        return null;
    }

    /** 409 checks shared by everything that sends lines. */
    private HttpResponse machineConflict(boolean motion) {
        if (!d.link().isConnected()) {
            return ApiErrors.conflict("not_connected", "UGS is not connected to a controller");
        }
        if (motion && (d.link().isStreaming() || Set.of("running", "paused").contains(d.state().streamState()))) {
            return ApiErrors.conflict("busy", "a file is streaming");
        }
        if (motion && d.state().interceptorActive()) {
            return ApiErrors.conflict("interceptor_active", "UGS is waiting for the operator (tool change)");
        }
        return null;
    }

    private HttpResponse command(JsonObject body) {
        JsonElement le = body.get("lines");
        if (le == null || !le.isJsonArray() || le.getAsJsonArray().isEmpty()) {
            throw new BadRequest("lines must be a non-empty list of strings");
        }
        JsonArray arr = le.getAsJsonArray();
        if (arr.size() > 200) {
            throw new BadRequest("at most 200 lines per request");
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new BadRequest("lines must be a non-empty list of strings");
            }
            lines.add(e.getAsString());
        }
        String tag = optString(body, "tag");
        String expect = optString(body, "expect");
        Integer timeout = optInt(body, "timeout_s");
        List<Classifier.Classification> classes = new ArrayList<>();
        Level needed = Level.OBSERVE;
        boolean motion = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) {
                throw new BadRequest("line " + i + " is empty");
            }
            String why = Classifier.invalidReason(raw);
            if (why != null) {
                throw new BadRequest("line " + i + ": " + why);
            }
            Classifier.Classification c = Classifier.classify(raw);
            classes.add(c);
            if (c.required().ordinal() > needed.ordinal()) {
                needed = c.required();
            }
            motion |= c.cls() != Classifier.LineClass.QUERY;
        }
        Level have = d.authority().level();
        for (Classifier.Classification c : classes) {
            if (!have.allows(c.required())) {
                return ApiErrors.forbidden(c.required(), "'" + c.line() + "' needs " + c.required().id()
                        + " authority (now " + have.id() + "); nothing was sent");
            }
        }
        HttpResponse conflict = machineConflict(motion);
        if (conflict != null) {
            return conflict;
        }
        if (motion) {
            d.authority().touch();
        }
        CommandService.Req r = d.commands().submit(lines, classes, tag, expect, timeout, null);
        JsonObject full = CommandService.toJson(r);
        JsonObject out = new JsonObject();
        out.add("id", full.get("id"));
        JsonArray ls = new JsonArray();
        for (JsonElement e : full.getAsJsonArray("lines")) {
            JsonObject l = e.getAsJsonObject();
            JsonObject o = new JsonObject();
            o.add("index", l.get("index"));
            o.add("line", l.get("line"));
            o.add("class", l.get("class"));
            o.add("state", l.get("state"));
            ls.add(o);
        }
        out.add("lines", ls);
        return HttpResponse.json(202, out.toString());
    }

    private HttpResponse realtime(JsonObject body) {
        String op = optString(body, "op");
        if (op == null || !REALTIME_OPS.contains(op)) {
            throw new BadRequest("op must be one of " + new java.util.TreeSet<>(REALTIME_OPS));
        }
        if (!d.link().isConnected()) {
            return ApiErrors.conflict("not_connected", "UGS is not connected to a controller");
        }
        if (op.equals("reset")) {
            d.commands().dropAll("reset by Claude");
        }
        try {
            String sent = d.link().realtime(op, LogRecord.Src.CLAUDE);
            if (op.equals("reset")) {
                d.authority().disarm("soft reset (by Claude)");
            }
            JsonObject o = new JsonObject();
            o.addProperty("sent", sent);
            return ok(o);
        } catch (IllegalStateException e) {
            return ApiErrors.conflict("not_connected", e.getMessage());
        } catch (Exception e) {
            return ApiErrors.error(500, "internal", "real-time " + op + " failed: " + e.getMessage());
        }
    }

    private HttpResponse fileLoad(JsonObject body) {
        HttpResponse refused = requireLevel(Level.MOTION, "loading a file");
        if (refused != null) {
            return refused;
        }
        String path = optString(body, "path");
        if (path == null || path.isBlank()) {
            throw new BadRequest("path is required");
        }
        Path p = Path.of(path);
        if (!p.isAbsolute()) {
            throw new BadRequest("path must be absolute");
        }
        if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
            throw new BadRequest("not a readable regular file: " + path);
        }
        if (d.link().isStreaming()) {
            return ApiErrors.conflict("busy", "a file is streaming");
        }
        try {
            FileFingerprint fp = FileFingerprint.of(p);
            d.authority().touch();
            d.link().loadFile(p.toFile());
            JsonObject o = new JsonObject();
            o.addProperty("path", fp.path().toString());
            o.addProperty("sha256", fp.sha256());
            o.addProperty("lines", fp.lines());
            o.addProperty("loaded_at", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            return ok(o);
        } catch (Exception e) {
            return ApiErrors.error(500, "internal", "UGS could not load the file: " + e.getMessage());
        }
    }

    private HttpResponse fileRun() {
        HttpResponse refused = requireLevel(Level.STREAM, "running a file");
        if (refused != null) {
            return refused;
        }
        Path p = loadedPath();
        if (p == null || !Files.isRegularFile(p)) {
            return ApiErrors.notFound("no file loaded");
        }
        HttpResponse conflict = machineConflict(true);
        if (conflict != null) {
            return conflict;
        }
        if (runPrompt != null) {
            return ApiErrors.conflict("busy", "a run request is already waiting for the operator");
        }
        FileFingerprint fp;
        try {
            fp = FileFingerprint.of(p);
        } catch (Exception e) {
            return ApiErrors.error(500, "internal", "could not hash the file: " + e.getMessage());
        }
        d.authority().touch();
        String id = "f-" + runCounter.incrementAndGet();
        logAuthority("Claude asks to run " + p.getFileName() + " (sha256 " + fp.sha256() + ")", LogRecord.Src.CLAUDE,
                Map.of("requested", "stream", "action", "file_run", "run_id", id, "path", p.toString(), "sha256", fp.sha256()));
        String msg = "Claude asks to run the loaded file:\n\n  " + p.getFileName() + "\n  " + fp.lines() + " lines\n  SHA-256 "
                + fp.sha256() + "\n\nStart streaming it now?";
        OperatorPrompts.Prompt prompt = d.prompts().confirm("Claude: run file", msg, "Start");
        runPrompt = prompt;
        prompt.result().whenComplete((approved, err) -> {
            runPrompt = null;
            boolean yes = Boolean.TRUE.equals(approved);
            String why = null;
            if (yes) {
                why = d.authority().isLocked() ? "locked by the operator"
                        : !d.authority().level().allows(Level.STREAM) ? "authority is now " + d.authority().level().id()
                        : !d.link().isConnected() ? "not connected" : d.link().isStreaming() ? "a file is streaming" : null;
                try {
                    if (why == null && !FileFingerprint.of(p).sha256().equals(fp.sha256())) {
                        why = "the file changed on disk";
                    }
                } catch (Exception e) {
                    why = "the file can't be read";
                }
            }
            Map<String, String> extra = Map.of("action", "file_run", "run_id", id, "approved", String.valueOf(yes && why == null));
            if (!yes) {
                logAuthority("operator declined running " + p.getFileName(), LogRecord.Src.OPERATOR, extra);
            } else if (why != null) {
                logAuthority("run of " + p.getFileName() + " not started: " + why, LogRecord.Src.BRIDGE, extra);
            } else {
                logAuthority("operator approved running " + p.getFileName() + " for Claude", LogRecord.Src.OPERATOR, extra);
                try {
                    d.link().startStream(LogRecord.Src.CLAUDE);
                } catch (Exception e) {
                    logAuthority("UGS could not start the stream: " + e.getMessage(), LogRecord.Src.BRIDGE, extra);
                }
            }
        });
        JsonObject o = new JsonObject();
        o.addProperty("state", "awaiting_operator");
        o.addProperty("id", id);
        o.addProperty("sha256", fp.sha256());
        return HttpResponse.json(202, o.toString());
    }

    private void logAuthority(String reason, String by, Map<String, String> extra) {
        Authority.Snapshot s = d.authority().snapshot();
        JsonObject o = new JsonObject();
        o.addProperty("locked", s.locked());
        o.addProperty("level", s.level().id());
        o.addProperty("reason", reason);
        o.addProperty("by", by);
        extra.forEach(o::addProperty);
        d.log().append(LogRecord.Kind.AUTHORITY, by, o);
    }

    private HttpResponse notify(JsonObject body) {
        String text = optString(body, "text");
        String level = body.has("level") ? optString(body, "level") : "info";
        if (text == null || text.isBlank() || level == null || !Set.of("info", "warn", "alert").contains(level)) {
            throw new BadRequest("need text and level info|warn|alert");
        }
        if (text.length() > 2000) {
            throw new BadRequest("text is longer than 2000 characters");
        }
        Notifier n = d.notifier().get();
        if (n == Notifier.NONE) {
            JsonObject o = new JsonObject();
            o.addProperty("trigger", "claude");
            o.addProperty("text", text);
            o.addProperty("level", level);
            o.add("sinks", new JsonObject());
            d.log().append(LogRecord.Kind.NOTIFY, LogRecord.Src.CLAUDE, o);
        } else {
            n.send("claude", text, level);
        }
        return HttpResponse.json(202, "{}");
    }

    private HttpResponse authorityRequest(JsonObject body) {
        String level = optString(body, "level");
        String reason = body.has("reason") ? optString(body, "reason") : "";
        if (level == null || !(level.equals("motion") || level.equals("stream")) || reason == null) {
            throw new BadRequest("level must be motion or stream, with a reason");
        }
        if (reason.length() > 500) {
            throw new BadRequest("reason is longer than 500 characters");
        }
        Level wanted = Level.parse(level);
        d.authority().request(wanted, reason);
        d.notifier().get().send("claude_request", "Claude asks for " + wanted.label() + " authority: " + reason, "info");
        return HttpResponse.json(202, "{}");
    }

    private HttpResponse originsApply(JsonObject body) {
        HttpResponse refused = requireLevel(Level.MOTION, "applying an origin");
        if (refused != null) {
            return refused;
        }
        String id = optString(body, "origin_id");
        if (id == null) {
            throw new BadRequest("origin_id is required");
        }
        OriginStore.Origin o;
        try {
            o = originStore().find(id).orElse(null);
        } catch (IOException | RuntimeException e) {
            return ApiErrors.error(500, "internal", "origins.json is unreadable");
        }
        if (o == null || o.wco == null || o.wco.length != 3) {
            return ApiErrors.notFound("no origin " + id);
        }
        String wcs = o.wcs == null ? "G54" : o.wcs;
        int pIndex = wcs.matches("G5[4-9]") ? wcs.charAt(2) - '4' + 1 : -1;
        if (pIndex < 1) {
            throw new BadRequest("origin " + id + " has an unknown WCS " + wcs);
        }
        HttpResponse conflict = machineConflict(true);
        if (conflict != null) {
            return conflict;
        }
        String g10 = String.format(Locale.ROOT, "G10 L2 P%d X%.3f Y%.3f Z%.3f", pIndex, o.wco[0], o.wco[1], o.wco[2]);
        List<String> lines = List.of(g10, "$#");
        List<Classifier.Classification> classes = lines.stream().map(Classifier::classify).toList();
        d.authority().touch();
        double[] want = o.wco.clone();
        CommandService.Req r = d.commands().submit(lines, classes, "origin-apply", "G10 then $# reads back " + wcs,
                30, req -> verifyOrigin(req, id, wcs, want));
        JsonObject out = new JsonObject();
        out.addProperty("command_id", r.id);
        return HttpResponse.json(202, out.toString());
    }

    private void verifyOrigin(CommandService.Req req, String originId, String wcs, double[] want) {
        JsonObject res = CommandService.toJson(req);
        if (!"done".equals(res.get("state").getAsString())) {
            return;
        }
        double[] got = null;
        for (JsonElement e : res.getAsJsonArray("lines").get(1).getAsJsonObject().getAsJsonArray("response")) {
            Matcher m = G54.matcher(e.getAsString());
            if (m.find() && m.group(1).equals(wcs)) {
                got = new double[]{Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))};
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("wcs", wcs);
        JsonArray w = new JsonArray();
        for (double v : want) {
            w.add(v);
        }
        o.add("wco", w);
        o.addProperty("method", "restored");
        o.addProperty("origin_id", originId);
        o.addProperty("command_id", req.id);
        boolean verified = got != null;
        if (got != null) {
            JsonArray g = new JsonArray();
            for (int i = 0; i < 3; i++) {
                g.add(got[i]);
                verified &= Math.abs(got[i] - want[i]) <= 0.01;
            }
            o.add("read_back", g);
        }
        o.addProperty("verified", verified);
        d.log().append(LogRecord.Kind.ORIGIN, LogRecord.Src.CLAUDE, o);
    }

    private HttpResponse resumePlan(JsonObject body) {
        JsonElement t = body.get("target");
        if (t == null || !t.isJsonObject()) {
            throw new BadRequest("target must be {line: n} | {operation: name|number} | {stopped: true}");
        }
        Map<String, Object> target = Json.GSON.fromJson(t, new TypeToken<Map<String, Object>>() { }.getType());
        Object op = target.get("operation");
        if (op instanceof Number n) {
            target.put("operation", String.valueOf(n.intValue()));
        }
        ResumeTarget rt;
        try {
            rt = ResumeTarget.fromJson(target);
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage());
        }
        Path p = loadedPath();
        if (p == null || !Files.isRegularFile(p)) {
            return ApiErrors.notFound("no file loaded");
        }
        MachineProfile profile = d.profile().model();
        if (profile == null) {
            return ApiErrors.error(500, "internal", "no machine profile");
        }
        try {
            JobModel job = JobModel.load(p);
            JobState journal = null;
            Optional<Path> jp = d.journal().get();
            if (jp.isPresent()) {
                JobState js = JobState.read(jp.get());
                if (js.file != null && job.sha256().equals(js.file.sha256)) {
                    journal = js;
                }
            }
            ResumeContext ctx = new ResumeContext();
            ctx.homed = d.state().homed();
            ctx.currentWco = d.state().wco();
            ctx.currentTool = optString(body, "tool");
            if (body.has("mid_contour") && body.get("mid_contour").isJsonPrimitive()) {
                ctx.midContour = body.get("mid_contour").getAsBoolean();
            }
            ResumePlan plan = ResumePlanner.plan(job, profile, journal, ctx, rt);
            if (plan.planId != null) {
                plans.put(plan.planId, plan);
            }
            JsonObject logData = new JsonObject();
            logData.addProperty("event", "resume_planned");
            logData.addProperty("file_sha256", plan.fileSha256);
            logData.addProperty("line", plan.resumeLine);
            logData.addProperty("plan_id", plan.planId);
            d.log().append(LogRecord.Kind.JOB, LogRecord.Src.CLAUDE, logData);
            return HttpResponse.json(200, Json.toJson(plan));
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage());
        } catch (IOException e) {
            return ApiErrors.error(500, "internal", "could not read the job: " + e.getMessage());
        }
    }

    private HttpResponse resume(JsonObject body) {
        HttpResponse refused = requireLevel(Level.STREAM, "resuming a job");
        if (refused != null) {
            return refused;
        }
        String id = optString(body, "plan_id");
        if (id == null) {
            throw new BadRequest("plan_id is required");
        }
        if (!plans.containsKey(id)) {
            return ApiErrors.notFound("no plan " + id + " (plans live until UGS restarts)");
        }
        return ApiErrors.error(501, "not_implemented",
                "running a resume plan needs the job runtime (packages J3/J4); the plan was not started");
    }

    // ------------------------------------------------------------------ helpers

    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    private static JsonObject parseBody(HttpRequest req) {
        if (req.body().length == 0) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(req.bodyText());
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new BadRequest(key + " must be a string");
        }
        return e.getAsString();
    }

    private static Integer optInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new BadRequest(key + " must be a number");
        }
        return (int) Math.ceil(e.getAsDouble());
    }

    private static HttpResponse ok(JsonObject o) {
        return HttpResponse.json(200, o.toString());
    }
}

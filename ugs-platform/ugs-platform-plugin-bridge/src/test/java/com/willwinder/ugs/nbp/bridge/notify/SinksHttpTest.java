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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;

import static com.willwinder.ugs.nbp.bridge.notify.NotifyConfigTest.CHAT;
import static com.willwinder.ugs.nbp.bridge.notify.NotifyConfigTest.TOKEN;
import static com.willwinder.ugs.nbp.bridge.notify.NotifyConfigTest.WEBHOOK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Sink HTTP calls against a loopback fake server; never the real Telegram/Slack. */
public class SinksHttpTest {
    FakeChatServer server;
    HttpClient client;

    @Before
    public void setUp() throws Exception {
        server = new FakeChatServer();
        client = Sinks.newClient();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void telegramSendMessageRequestShape() {
        Sinks.Result r = new Sinks.Telegram(client, server.base(), TOKEN, CHAT, 10).send("✔ Job finished: a.nc — 61 min");
        assertEquals(Sinks.OK, r.status());
        assertNull(r.detail());
        assertEquals(1, server.requests.size());
        FakeChatServer.Request req = server.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals("/bot" + TOKEN + "/sendMessage", req.path());
        assertTrue(req.contentType(), req.contentType().startsWith("application/json"));
        JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
        assertEquals(987654321L, body.get("chat_id").getAsLong());
        assertTrue(body.get("chat_id").getAsJsonPrimitive().isNumber());
        assertEquals("✔ Job finished: a.nc — 61 min", body.get("text").getAsString());
        assertTrue(body.get("disable_web_page_preview").getAsBoolean());
        assertFalse("plain text, no parse_mode", body.has("parse_mode"));
    }

    @Test
    public void telegramChannelNameIsSentAsString() {
        new Sinks.Telegram(client, server.base(), TOKEN, "@shop_alerts", 10).send("x");
        JsonObject body = JsonParser.parseString(server.requests.get(0).body()).getAsJsonObject();
        assertEquals("@shop_alerts", body.get("chat_id").getAsString());
    }

    @Test
    public void slackWebhookRequestShape() {
        Sinks.Result r = new Sinks.Slack(client, WEBHOOK, server.base(), 10).send("⚠ ALARM 4: probe fail");
        assertEquals(Sinks.OK, r.status());
        FakeChatServer.Request req = server.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals(java.net.URI.create(WEBHOOK).getPath(), req.path());
        assertTrue(req.contentType().startsWith("application/json"));
        JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
        assertEquals("⚠ ALARM 4: probe fail", body.get("text").getAsString());
        assertEquals(1, body.size());
    }

    @Test
    public void serverErrorIsRetriedOnce() {
        server.enqueue(502, "bad gateway");
        assertEquals(Sinks.OK, new Sinks.Slack(client, WEBHOOK, server.base(), 10).send("x").status());
        assertEquals(2, server.requests.size());
    }

    @Test
    public void twoServerErrorsFail() {
        server.enqueue(500, "{\"ok\":false,\"description\":\"Internal\"}");
        server.enqueue(500, "{\"ok\":false,\"description\":\"Internal\"}");
        Sinks.Result r = new Sinks.Telegram(client, server.base(), TOKEN, CHAT, 10).send("x");
        assertEquals(Sinks.FAILED, r.status());
        assertEquals("HTTP 500: Internal", r.detail());
        assertEquals(2, server.requests.size());
    }

    @Test
    public void rateLimitedByTelegramIsRetriedAfterRetryAfter() {
        server.enqueue(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 0\","
                + "\"parameters\":{\"retry_after\":0}}");
        assertEquals(Sinks.OK, new Sinks.Telegram(client, server.base(), TOKEN, CHAT, 10).send("x").status());
        assertEquals(2, server.requests.size());
    }

    @Test
    public void unauthorizedIsNotRetriedAndDetailHasNoSecret() {
        server.enqueue(401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        Sinks.Result r = new Sinks.Telegram(client, server.base(), TOKEN, CHAT, 10).send("x");
        assertEquals(Sinks.FAILED, r.status());
        assertEquals("HTTP 401: Unauthorized", r.detail());
        assertEquals(1, server.requests.size());
    }

    @Test
    public void slackErrorCodeIsReported() {
        server.enqueue(404, "no_service");
        Sinks.Result r = new Sinks.Slack(client, WEBHOOK, server.base(), 10).send("x");
        assertEquals("HTTP 404: no_service", r.detail());
    }

    @Test
    public void telegramOkFalseIsAFailure() {
        server.enqueue(200, "{\"ok\":false}");
        assertEquals(Sinks.FAILED, new Sinks.Telegram(client, server.base(), TOKEN, CHAT, 10).send("x").status());
    }

    @Test
    public void connectionRefusedFailsAfterOneRetryWithoutLeakingTheToken() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        URI dead = URI.create("http://127.0.0.1:" + port);
        Sinks.Result r = new Sinks.Telegram(client, dead, TOKEN, CHAT, 10).send("x");
        assertEquals(Sinks.FAILED, r.status());
        assertTrue(r.detail(), r.detail().startsWith("ConnectException"));
        assertFalse(r.detail().contains(TOKEN));
    }
}

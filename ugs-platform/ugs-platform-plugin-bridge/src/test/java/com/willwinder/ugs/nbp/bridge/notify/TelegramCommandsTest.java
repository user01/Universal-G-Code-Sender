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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Telegram command loop with a fake Bot API: no network. */
public class TelegramCommandsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    SessionLog log;
    final AtomicReference<NotifyConfig> config = new AtomicReference<>();
    final FakeTransport transport = new FakeTransport();
    final FakeMachine machine = new FakeMachine();
    TelegramCommands bot;
    int updateId = 100;

    @Before
    public void setUp() throws Exception {
        log = new SessionLog(tmp.newFolder("home").toPath());
        log.start();
        config.set(NotifyConfig.parse(NotifyConfigTest.ready(true, false)));
        assertEquals(NotifyConfig.SinkState.READY, config.get().telegramState());
        bot = new TelegramCommands(config::get, transport, log);
        bot.setMachine(machine);
    }

    @After
    public void tearDown() {
        bot.close();
        log.close();
    }

    static final class FakeTransport implements TelegramCommands.Transport {
        final Deque<Object> responses = new ArrayDeque<>(); // String body or IOException
        final List<long[]> polls = new ArrayList<>();       // {offset, timeout}
        final List<String[]> sent = new ArrayList<>();      // {chat, text}
        final List<String> tokens = new ArrayList<>();

        @Override
        public String getUpdates(String token, long offset, int timeoutS) throws IOException {
            tokens.add(token);
            polls.add(new long[]{offset, timeoutS});
            Object r = responses.isEmpty() ? "{\"ok\":true,\"result\":[]}" : responses.removeFirst();
            if (r instanceof IOException e) {
                throw e;
            }
            return (String) r;
        }

        @Override
        public void sendMessage(String token, String chatId, String text) {
            tokens.add(token);
            sent.add(new String[]{chatId, text});
        }
    }

    static final class FakeMachine implements TelegramCommands.Machine {
        boolean running = true;
        int pauses;

        @Override
        public String status() {
            return "State: Run";
        }

        @Override
        public String eta() {
            return "~20 min left";
        }

        @Override
        public String tools() {
            return "▶ T21";
        }

        @Override
        public String log(int n) {
            return "last " + n;
        }

        @Override
        public boolean jobRunning() {
            return running;
        }

        @Override
        public String pause() {
            pauses++;
            return "0x21";
        }
    }

    String message(long chat, String type, String text) {
        JsonObject chatObj = new JsonObject();
        chatObj.addProperty("id", chat);
        chatObj.addProperty("type", type);
        JsonObject msg = new JsonObject();
        msg.add("chat", chatObj);
        msg.addProperty("text", text);
        JsonObject u = new JsonObject();
        u.addProperty("update_id", ++updateId);
        u.add("message", msg);
        return u.toString();
    }

    String updates(String... us) {
        JsonArray a = new JsonArray();
        for (String u : us) {
            a.add(JsonParser.parseString(u));
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("result", a);
        return o.toString();
    }

    long me() {
        return Long.parseLong(NotifyConfigTest.CHAT);
    }

    /** First poll skips the backlog; then {@code texts} are answered. */
    List<String> ask(String... texts) throws Exception {
        if (bot.offset() == 0) {
            assertEquals(0, bot.pollOnce());
        }
        String[] us = new String[texts.length];
        for (int i = 0; i < texts.length; i++) {
            us[i] = message(me(), "private", texts[i]);
        }
        int before = transport.sent.size();
        transport.responses.add(updates(us));
        assertEquals(0, bot.pollOnce());
        List<String> out = new ArrayList<>();
        for (String[] s : transport.sent.subList(before, transport.sent.size())) {
            assertEquals(NotifyConfigTest.CHAT, s[0]);
            out.add(s[1]);
        }
        return out;
    }

    List<JsonObject> clientRecords() throws Exception {
        log.awaitWritten(2000);
        List<JsonObject> out = new ArrayList<>();
        for (LogRecord r : log.since(0, Set.of(LogRecord.Kind.CLIENT), 10_000)) {
            out.add(JsonParser.parseString(r.json()).getAsJsonObject().getAsJsonObject("data"));
        }
        return out;
    }

    @Test
    public void theBacklogIsSkippedAndOffsetsAdvance() throws Exception {
        transport.responses.add(updates(message(me(), "private", "pause")));
        assertEquals(0, bot.pollOnce());
        assertEquals(0, machine.pauses);              // an old pause never fires late
        assertTrue(transport.sent.isEmpty());
        assertEquals(0, transport.polls.get(0)[1]);   // backlog poll doesn't wait
        assertEquals(updateId + 1, bot.offset());
        ask("status");
        long[] second = transport.polls.get(1);
        assertEquals(updateId, second[0]);            // offset = last update + 1 (before this one)
        assertEquals(TelegramCommands.POLL_TIMEOUT_S, second[1]);
        assertEquals(updateId + 1, bot.offset());
        assertTrue(clientRecords().stream().anyMatch(d -> "skipped_backlog".equals(d.get("action").getAsString())));
    }

    @Test
    public void eachCommand() throws Exception {
        assertEquals(List.of("State: Run", "~20 min left", "▶ T21", "last 5", "State: Run", "~20 min left"),
                ask("status", "ETA", "/tools", "log", "/status@my_cnc_bot", "  Eta please"));
        List<String> help = ask("help", "/start", "what?");
        for (String h : help) {
            assertTrue(h, h.startsWith("Commands: status · eta · tools · log · pause (feed hold) · help."));
        }
    }

    @Test
    public void machineActionsAreRefused() throws Exception {
        for (String t : List.of("start", "resume", "/home", "unlock", "jog x10", "M3 S18000", "$H", "$X", "~",
                "G0 X0", "cycle start", "stop")) {
            String r = ask(t).get(0);
            assertTrue(t + " -> " + r, r.startsWith(TelegramCommands.AT_THE_MACHINE));
        }
        assertEquals(0, machine.pauses);
    }

    @Test
    public void pauseOnlyWhileRunning() throws Exception {
        assertTrue(ask("pause").get(0).startsWith("⏸ Feed hold sent (0x21)."));
        assertEquals(1, machine.pauses);
        machine.running = false;
        assertEquals("Nothing to pause: no job is running.", ask("/pause").get(0));
        assertEquals(1, machine.pauses);
        NotifyConfig.Builder b = config.get().toBuilder();
        b.telegramAllowPause = false;
        config.set(b.build());
        machine.running = true;
        assertTrue(ask("hold").get(0).startsWith("Pause from Telegram is off"));
        assertEquals(1, machine.pauses);
        assertTrue(clientRecords().stream().anyMatch(d -> "pause".equals(d.get("action").getAsString())
                && "feed hold sent".equals(d.get("detail").getAsString())));
    }

    @Test
    public void otherChatsAndGroupsAreIgnoredAndLoggedOnce() throws Exception {
        bot.pollOnce(); // backlog
        transport.responses.add(updates(message(555, "private", "status"), message(555, "private", "pause"),
                message(me(), "group", "status"), message(-me(), "supergroup", "pause")));
        bot.pollOnce();
        assertTrue(transport.sent.isEmpty());
        assertEquals(0, machine.pauses);
        long ignored = clientRecords().stream().filter(d -> "ignored_sender".equals(d.get("action").getAsString())).count();
        assertEquals("555 once, my id (group) once, the supergroup once", 3, ignored);
    }

    @Test
    public void networkErrorsBackOffAndNeverLogTheToken() throws Exception {
        String token = config.get().botToken;
        transport.responses.add(new IOException("connect failed for https://api.telegram.org/bot" + token + "/getUpdates"));
        transport.responses.add(new IOException("again"));
        transport.responses.add("{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        assertEquals(1_000, bot.pollOnce());
        assertEquals(2_000, bot.pollOnce());
        assertEquals(4_000, bot.pollOnce());
        assertEquals(0, bot.pollOnce()); // recovers
        for (int i = 0; i < 10; i++) {
            transport.responses.add(new IOException("down"));
        }
        long wait = 0;
        for (int i = 0; i < 10; i++) {
            wait = bot.pollOnce();
        }
        assertEquals(TelegramCommands.MAX_BACKOFF_MS, wait);
        log.awaitWritten(2000);
        for (LogRecord r : log.since(0, null, 100_000)) {
            assertFalse(r.json(), r.json().contains(token));
            assertFalse(r.json(), r.json().contains(token.substring(token.indexOf(':') + 1)));
        }
        String file = Files.readString(log.sessionDir().resolve(SessionLog.LOG_FILE));
        assertFalse(file.contains(token.substring(token.indexOf(':') + 1)));
    }

    @Test
    public void offWhenTelegramIsNotReadyOrCommandsAreOff() throws Exception {
        NotifyConfig.Builder b = config.get().toBuilder();
        b.telegramCommands = false;
        config.set(b.build());
        assertEquals(5_000, bot.pollOnce());
        config.set(NotifyConfig.defaults());
        assertEquals(5_000, bot.pollOnce());
        assertTrue(transport.polls.isEmpty());
    }

    @Test
    public void withoutTheApiItSaysSo() throws Exception {
        bot.setMachine(null);
        assertTrue(ask("status").get(0).startsWith("The UGS bridge isn't running"));
    }
}

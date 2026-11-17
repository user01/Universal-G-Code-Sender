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
import com.willwinder.ugs.nbp.bridge.eta.EtaTracker;
import com.willwinder.ugs.nbp.bridge.job.estimate.Estimate;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.ugs.nbp.bridge.notify.Messages;
import com.willwinder.ugs.nbp.bridge.notify.TelegramCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The machine side of the Telegram commands (D26): plain-text answers from the API's state, and the operator's feed
 * hold. Read-only apart from {@link #pause()}, which is not subject to Claude's lock (it is the operator's action).
 */
public final class TelegramAccess implements TelegramCommands.Machine {
    private static final Set<String> NOTABLE = Set.of(LogRecord.Kind.JOB, LogRecord.Kind.TOOL, LogRecord.Kind.EVENT,
            LogRecord.Kind.SESSION, LogRecord.Kind.NOTIFY);

    private final StateTracker state;
    private final EtaTracker eta;
    private final Authority authority;
    private final MachineLink link;
    private final SessionLog log;

    public TelegramAccess(StateTracker state, EtaTracker eta, Authority authority, MachineLink link, SessionLog log) {
        this.state = state;
        this.eta = eta;
        this.authority = authority;
        this.link = link;
        this.log = log;
    }

    @Override
    public String status() {
        if (!state.connected()) {
            return "UGS is not connected to the controller.";
        }
        JsonObject c = state.controller();
        StringBuilder s = new StringBuilder();
        String st = c == null ? "unknown" : str(c, "state");
        s.append("State: ").append(st);
        if (c != null) {
            String w = xyz(c.get("wpos"));
            String m = xyz(c.get("mpos"));
            if (w != null) {
                s.append("\nWork: ").append(w);
            }
            if (m != null) {
                s.append("\nMachine: ").append(m);
            }
        }
        JsonObject f = new JsonObject();
        state.fillStatus(f);
        JsonObject alarm = f.has("alarm") && f.get("alarm").isJsonObject() ? f.getAsJsonObject("alarm") : null;
        if (alarm != null) {
            s.append("\nALARM ").append(alarm.get("code").getAsInt()).append(": ").append(str(alarm, "text"));
        }
        s.append("\nHomed: ").append(state.homed() ? "yes" : "no");
        String stream = state.streamState();
        EtaTracker.Snapshot e = eta == null ? null : eta.snapshot();
        if (!"idle".equals(stream)) {
            s.append("\nJob: ").append(stream);
            if (e != null && e.file() != null) {
                s.append(" — ").append(e.file());
            }
            if (e != null) {
                int total = e.estimate() == null ? 0 : e.estimate().lines();
                s.append(", line ").append(Messages.count(e.ackedLine()));
                if (total > 0) {
                    s.append(" of ").append(Messages.count(total));
                }
                if (e.currentOp() != null || e.currentTool() != null) {
                    s.append("\nNow: ").append(join(e.currentTool(), e.currentOp() == null ? null : e.currentOp().name()));
                }
            }
        } else {
            s.append("\nJob: none running");
        }
        if (state.interceptorActive()) {
            s.append("\nTool change in progress: UGS is waiting for you.");
        }
        Authority.Snapshot a = authority.snapshot();
        s.append("\nClaude: ").append(a.locked() ? "locked out" : "allowed, " + a.level().id());
        return s.toString();
    }

    @Override
    public String eta() {
        EtaTracker.Snapshot e = eta == null ? null : eta.snapshot();
        if (e == null) {
            return "No job has run since UGS started.";
        }
        if (!e.active()) {
            StringBuilder s = new StringBuilder("Last job ").append(e.file() == null ? "" : e.file()).append(": ")
                    .append(e.result() == null ? "ended" : e.result()).append(" after ").append(Messages.duration(e.elapsedS()));
            if (e.estimate() != null) {
                s.append(Messages.actualVsEstimate(e.machineS(), e.estimate().machineS()));
            }
            return s.append('.').toString();
        }
        if (e.estimate() == null) {
            return e.file() + ": running for " + Messages.duration(e.elapsedS()) + "; the estimate isn't ready yet.";
        }
        Double f = e.fraction();
        StringBuilder s = new StringBuilder(e.file() == null ? "Job" : e.file());
        if (f != null) {
            s.append(": ").append(Math.round(f * 100)).append(" % done");
        }
        if (e.currentOp() != null || e.currentTool() != null) {
            s.append("\nNow: ").append(join(e.currentTool(), e.currentOp() == null ? null : e.currentOp().name()));
            if (e.opRemainingS() != null) {
                s.append(", ~").append(Messages.duration(e.opRemainingS())).append(" left in this operation");
            }
        }
        s.append("\nJob: ~").append(Messages.duration(e.adjustedRemainingS())).append(" left");
        String clock = Messages.clock(e.predictedFinish());
        if (clock != null) {
            s.append(", done ≈ ").append(clock);
        }
        if (e.toolChangesRemaining() > 0) {
            s.append(" (+").append(e.toolChangesRemaining())
                    .append(e.toolChangesRemaining() == 1 ? " tool change" : " tool changes").append(" waiting on you)");
        }
        if (e.waitingForToolChange()) {
            s.append("\nWaiting for you to change the tool.");
        }
        s.append(String.format(Locale.ROOT, "\nRunning %s, paused %s; speed vs estimate ×%.2f.",
                Messages.duration(e.elapsedS()), Messages.duration(e.pausedS()), e.ratio()));
        return s.toString();
    }

    @Override
    public String tools() {
        EtaTracker.Snapshot e = eta == null ? null : eta.snapshot();
        Estimate est = e == null ? null : e.estimate();
        if (est == null) {
            return "No job with a time estimate yet.";
        }
        StringBuilder s = new StringBuilder("Tools in ").append(est.name()).append(':');
        String current = e.currentTool();
        boolean seenCurrent = current == null;
        for (Estimate.Tool t : est.tools()) {
            if (t.number() == 0) {
                continue;
            }
            String id = "T" + t.number();
            String mark;
            if (!e.active()) {
                mark = "✓";
            } else if (id.equals(current)) {
                mark = "▶";
                seenCurrent = true;
            } else {
                mark = seenCurrent ? "·" : "✓";
            }
            s.append('\n').append(mark).append(' ').append(t.id() == null ? id : t.id());
            if (t.name() != null) {
                s.append(' ').append(t.name());
            }
            s.append(" — ").append(Messages.duration(t.seconds()));
        }
        return s.toString();
    }

    @Override
    public String log(int n) {
        List<LogRecord> recs = log.since(Math.max(0, log.oldestSeqInRing() - 1), NOTABLE, Integer.MAX_VALUE);
        List<String> lines = new ArrayList<>();
        for (int i = recs.size() - 1; i >= 0 && lines.size() < n; i--) {
            String line = describe(recs.get(i));
            if (line != null) {
                lines.add(0, line);
            }
        }
        return lines.isEmpty() ? "Nothing notable logged yet." : String.join("\n", lines);
    }

    /** One notable record as text, or null for routine ones. */
    static String describe(LogRecord r) {
        JsonObject rec;
        try {
            rec = JsonParser.parseString(r.json()).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        String t = str(rec, "t");
        String time = t != null && t.length() >= 19 ? t.substring(11, 19) : "";
        JsonObject d = rec.has("data") && rec.get("data").isJsonObject() ? rec.getAsJsonObject("data") : new JsonObject();
        String what = switch (r.kind()) {
            case LogRecord.Kind.JOB -> "job " + str(d, "event") + (str(d, "op") != null ? " " + str(d, "op") : "");
            case LogRecord.Kind.TOOL -> "tool " + str(d, "event") + (str(d, "tool") != null ? " " + str(d, "tool") : "");
            case LogRecord.Kind.NOTIFY -> sentTrigger(str(d, "trigger")) ? "sent: " + abbreviate(str(d, "text")) : null;
            case LogRecord.Kind.SESSION -> switch (String.valueOf(str(d, "event"))) {
                case "connect" -> "connected";
                case "disconnect" -> "disconnected";
                default -> null;
            };
            case LogRecord.Kind.EVENT -> switch (String.valueOf(str(d, "type"))) {
                case "alarm" -> d.has("code") ? "ALARM " + d.get("code").getAsInt() : null;
                case "stream" -> "stream " + str(d, "event");
                case "controller_silent" -> "controller stopped answering";
                case "controller_responding" -> "controller answering again";
                default -> null;
            };
            default -> null;
        };
        return what == null ? null : time + " " + what;
    }

    private static boolean sentTrigger(String trigger) {
        return trigger != null && !"config".equals(trigger) && !"test".equals(trigger);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 80 ? s.substring(0, 79) + "…" : s;
    }

    @Override
    public boolean jobRunning() {
        return "running".equals(state.streamState()) && link.isConnected();
    }

    @Override
    public String pause() throws Exception {
        return link.realtime("hold", LogRecord.Src.OPERATOR);
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " " + b;
    }

    private static String xyz(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) {
            return null;
        }
        return String.format(Locale.ROOT, "X%.2f Y%.2f Z%.2f", a.get(0).getAsDouble(), a.get(1).getAsDouble(),
                a.get(2).getAsDouble());
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}

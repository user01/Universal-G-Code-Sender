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
package com.willwinder.ugs.nbp.bridge.capture;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Status de-duplication (log-format.md "status"): a report is logged when it differs from the last logged
 * one, ignoring {@code raw} and feed/spindle ({@code FS}) jitter below 1%. While reports keep arriving
 * unchanged, the latest one is logged at least once a second as a heartbeat ({@code "heartbeat": true}).
 * No heartbeat is invented when no report arrived since the last logged one.
 */
final class StatusFilter {
    static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final double JITTER = 0.01;
    private static final Set<String> IGNORED = Set.of("raw", "heartbeat");
    private static final Set<String> JITTER_FIELDS = Set.of("feed", "spindle");

    private JsonObject lastEmitted;
    private long lastEmitNanos;
    private JsonObject latest;
    private boolean latestPending;

    synchronized void reset() {
        lastEmitted = null;
        latest = null;
        latestPending = false;
    }

    /** Offers a new report; returns the object to log now, or null. */
    synchronized JsonObject offer(JsonObject status, long nowNanos) {
        latest = status;
        if (lastEmitted == null || changed(lastEmitted, status)) {
            return emit(status, nowNanos, false);
        }
        if (nowNanos - lastEmitNanos >= HEARTBEAT_NANOS) {
            return emit(status, nowNanos, true);
        }
        latestPending = true;
        return null;
    }

    /** Called periodically; returns a heartbeat to log, or null. */
    synchronized JsonObject tick(long nowNanos) {
        if (latestPending && latest != null && nowNanos - lastEmitNanos >= HEARTBEAT_NANOS) {
            return emit(latest, nowNanos, true);
        }
        return null;
    }

    private JsonObject emit(JsonObject status, long nowNanos, boolean heartbeat) {
        lastEmitted = status;
        lastEmitNanos = nowNanos;
        latestPending = false;
        JsonObject out = status.deepCopy();
        if (heartbeat) {
            out.addProperty("heartbeat", true);
        }
        return out;
    }

    static boolean changed(JsonObject a, JsonObject b) {
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            if (IGNORED.contains(k)) {
                continue;
            }
            JsonElement x = a.has(k) ? a.get(k) : JsonNull.INSTANCE;
            JsonElement y = b.has(k) ? b.get(k) : JsonNull.INSTANCE;
            if (JITTER_FIELDS.contains(k) && isNumber(x) && isNumber(y)) {
                double u = x.getAsDouble();
                double v = y.getAsDouble();
                if (Math.abs(u - v) > JITTER * Math.max(Math.abs(u), Math.abs(v))) {
                    return true;
                }
                continue;
            }
            if (!x.equals(y)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }
}

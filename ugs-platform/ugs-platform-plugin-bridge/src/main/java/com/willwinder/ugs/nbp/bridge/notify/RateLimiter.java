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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sliding one-minute rate limit plus de-duplication of identical texts.
 * <ul>
 *     <li>A text identical to one sent less than {@code dedupSeconds} ago is a {@link Decision#DUPLICATE}.</li>
 *     <li>More than {@code perMinute} sends within 60 s are {@link Decision#RATE_LIMITED}, except
 *     {@code alert} level (ALARM, connection lost, stream error), which always goes out but still counts.</li>
 * </ul>
 */
final class RateLimiter {
    enum Decision {SEND, RATE_LIMITED, DUPLICATE}

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    private final Deque<Long> sent = new ArrayDeque<>();
    private final Map<String, Long> lastByText = new HashMap<>();

    synchronized Decision check(String text, String level, long now, int perMinute, int dedupSeconds) {
        long dedupWindow = TimeUnit.SECONDS.toNanos(dedupSeconds);
        for (Iterator<Map.Entry<String, Long>> it = lastByText.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() >= dedupWindow) {
                it.remove();
            }
        }
        while (!sent.isEmpty() && now - sent.peekFirst() >= MINUTE) {
            sent.removeFirst();
        }
        if (dedupSeconds > 0 && lastByText.containsKey(text)) {
            return Decision.DUPLICATE;
        }
        if (sent.size() >= perMinute && !Triggers.ALERT.equals(level)) {
            return Decision.RATE_LIMITED;
        }
        sent.addLast(now);
        if (dedupSeconds > 0) {
            lastByText.put(text, now);
        }
        return Decision.SEND;
    }
}

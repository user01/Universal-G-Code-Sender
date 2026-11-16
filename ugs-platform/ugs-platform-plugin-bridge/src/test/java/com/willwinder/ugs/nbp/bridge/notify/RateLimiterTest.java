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

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static com.willwinder.ugs.nbp.bridge.notify.RateLimiter.Decision.DUPLICATE;
import static com.willwinder.ugs.nbp.bridge.notify.RateLimiter.Decision.RATE_LIMITED;
import static com.willwinder.ugs.nbp.bridge.notify.RateLimiter.Decision.SEND;
import static org.junit.Assert.assertEquals;

public class RateLimiterTest {
    static long s(double seconds) {
        return (long) (seconds * TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    public void limitsPerSlidingMinute() {
        RateLimiter r = new RateLimiter();
        for (int i = 0; i < 3; i++) {
            assertEquals(SEND, r.check("m" + i, "info", s(i), 3, 120));
        }
        assertEquals(RATE_LIMITED, r.check("m3", "info", s(10), 3, 120));
        assertEquals(RATE_LIMITED, r.check("m4", "warn", s(59.9), 3, 120));
        // the first send leaves the window at t=60
        assertEquals(SEND, r.check("m5", "info", s(60), 3, 120));
    }

    @Test
    public void alertsBypassTheRateLimitButCount() {
        RateLimiter r = new RateLimiter();
        assertEquals(SEND, r.check("a", "info", s(0), 1, 120));
        assertEquals(RATE_LIMITED, r.check("b", "info", s(1), 1, 120));
        assertEquals(SEND, r.check("⚠ ALARM 1", "alert", s(2), 1, 120));
    }

    @Test
    public void identicalTextsAreSuppressedWithinTheWindow() {
        RateLimiter r = new RateLimiter();
        assertEquals(SEND, r.check("⚠ ALARM 4", "alert", s(0), 6, 120));
        assertEquals(DUPLICATE, r.check("⚠ ALARM 4", "alert", s(30), 6, 120));
        assertEquals(DUPLICATE, r.check("⚠ ALARM 4", "alert", s(119), 6, 120));
        assertEquals(SEND, r.check("⚠ ALARM 4", "alert", s(120), 6, 120));
        assertEquals(SEND, r.check("⚠ ALARM 5", "alert", s(121), 6, 120));
    }

    @Test
    public void dedupZeroTurnsDedupOff() {
        RateLimiter r = new RateLimiter();
        assertEquals(SEND, r.check("x", "info", s(0), 6, 0));
        assertEquals(SEND, r.check("x", "info", s(1), 6, 0));
    }

    @Test
    public void suppressedAttemptsDoNotConsumeTheBudget() {
        RateLimiter r = new RateLimiter();
        assertEquals(SEND, r.check("x", "info", s(0), 2, 120));
        assertEquals(DUPLICATE, r.check("x", "info", s(1), 2, 120));
        assertEquals(SEND, r.check("y", "info", s(2), 2, 120));
        assertEquals(RATE_LIMITED, r.check("z", "info", s(3), 2, 120));
    }
}

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

/** Trigger names used in {@code notify} records and in the {@code triggers} block of notify.json. */
public final class Triggers {
    public static final String STREAM_FINISHED = "stream_finished";
    public static final String STREAM_ERROR = "stream_error";
    public static final String ALARM = "alarm";
    /** Config key is {@code feed_hold_seconds} (0 = off). */
    public static final String FEED_HOLD = "feed_hold";
    public static final String TOOL_CHANGE_WAITING = "tool_change_waiting";
    public static final String CONNECTION_LOST = "connection_lost";
    public static final String CLAUDE_AUTHORITY_REQUEST = "claude_authority_request";
    public static final String CLAUDE_MESSAGE = "claude_message";
    /** Config key is {@code progress_every_percent} (0 = off). */
    public static final String PROGRESS = "progress";
    /** The "Send test" button; ignores trigger toggles, rate limit and de-dup. */
    public static final String TEST = "test";
    /** Configuration status (sinks disabled, notify.json invalid); logged only, never sent. */
    public static final String CONFIG = "config";

    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String ALERT = "alert";

    private Triggers() {
    }

    /** Normalizes a level to info|warn|alert (unknown values become info). */
    public static String level(String level) {
        if (WARN.equalsIgnoreCase(level)) {
            return WARN;
        }
        if (ALERT.equalsIgnoreCase(level)) {
            return ALERT;
        }
        return INFO;
    }
}

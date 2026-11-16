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

/**
 * Sends a short operator notification (Telegram/Slack, PLAN D5). Implementations never block the caller.
 * <p>
 * {@code trigger} names the cause ({@code claude_message}, {@code claude_authority_request}, {@code alarm}, ...;
 * see {@link Triggers}); {@code level} is {@code info}, {@code warn} or {@code alert}.
 */
public interface Notifier {
    void send(String trigger, String text, String level);
}

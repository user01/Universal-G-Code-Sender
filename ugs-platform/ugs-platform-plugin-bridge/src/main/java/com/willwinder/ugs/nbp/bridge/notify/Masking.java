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

import java.net.URI;
import java.net.URISyntaxException;

/** Masks secrets (bot token, webhook URL) for logs, records and the panel. */
public final class Masking {
    private Masking() {
    }

    /** {@code 123456789:AAH…xyz} gives {@code 1234…(46)…yz}; short or placeholder values give {@code ****}. */
    public static String token(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        if (NotifyConfig.isPlaceholder(token)) {
            return "(placeholder)";
        }
        if (token.length() < 12) {
            return "****";
        }
        return token.substring(0, 4) + "…(" + token.length() + ")…" + token.substring(token.length() - 2);
    }

    /** Chat ids aren't secret but needn't be in logs either: keeps the last 3 digits. */
    public static String chatId(String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            return "";
        }
        if (NotifyConfig.isPlaceholder(chatId)) {
            return "(placeholder)";
        }
        return chatId.length() <= 3 ? "***" : "…" + chatId.substring(chatId.length() - 3);
    }

    /** Keeps scheme and host, hides the path: {@code https://hooks.slack.com/****}. */
    public static String url(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        if (NotifyConfig.isPlaceholder(url)) {
            return "(placeholder)";
        }
        try {
            URI u = new URI(url);
            if (u.getScheme() != null && u.getHost() != null) {
                return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/****";
            }
        } catch (URISyntaxException e) {
            // fall through
        }
        return "****";
    }

    /** Replaces every occurrence of the given secrets in {@code text} (for error messages that might echo a URL). */
    public static String scrub(String text, String... secrets) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String s : secrets) {
            if (s != null && s.length() >= 6) {
                out = out.replace(s, "****");
            }
        }
        return out;
    }
}

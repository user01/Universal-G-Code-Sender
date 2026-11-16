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
package com.willwinder.ugs.nbp.bridge.api.http;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed HTTP request. Header names are lower case; the path is URL-decoded and has no query.
 */
public record HttpRequest(String method, String path, Map<String, String> query, Map<String, String> headers,
                          byte[] body, String remote) {

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String param(String name) {
        return query.get(name);
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public boolean isWebSocketUpgrade() {
        String upgrade = header("upgrade");
        String connection = header("connection");
        return "GET".equals(method) && upgrade != null && upgrade.equalsIgnoreCase("websocket")
                && connection != null && connection.toLowerCase(Locale.ROOT).contains("upgrade");
    }
}

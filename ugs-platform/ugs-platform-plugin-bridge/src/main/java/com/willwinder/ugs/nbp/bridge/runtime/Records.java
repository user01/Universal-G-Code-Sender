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
package com.willwinder.ugs.nbp.bridge.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;

/** Where the runtime's {@code origin}, {@code tool} and {@code job} records go (the session log, or a test list). */
@FunctionalInterface
public interface Records {
    void append(String kind, String src, JsonObject data);

    static Records of(SessionLog log) {
        return log == null ? (k, s, d) -> { } : log::append;
    }

    static com.google.gson.JsonElement array(double[] v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        JsonArray a = new JsonArray();
        for (double d : v) {
            a.add(d);
        }
        return a;
    }
}

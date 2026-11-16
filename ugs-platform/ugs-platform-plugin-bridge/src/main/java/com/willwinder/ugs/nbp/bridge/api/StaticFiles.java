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

import com.willwinder.ugs.nbp.bridge.api.http.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the viewer ({@code bridge/viewer}, packaged into the module jar under {@value #ROOT}) at {@code /view/}.
 * The files are public (no token); everything they load from the API needs the viewer token.
 */
public final class StaticFiles {
    static final String ROOT = "/com/willwinder/ugs/nbp/bridge/viewer/";
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("glb", "model/gltf-binary"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/plain; charset=utf-8"));

    private StaticFiles() {
    }

    /** @param rel the path below {@code /view/} ("" for the index) */
    static HttpResponse serve(String rel) {
        if (rel.isEmpty() || rel.endsWith("/")) {
            rel = rel + "index.html";
        }
        if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/") || rel.contains("//")) {
            return ApiErrors.error(404, "not_found", "no such file");
        }
        try (InputStream in = StaticFiles.class.getResourceAsStream(ROOT + rel)) {
            if (in == null) {
                return ApiErrors.error(404, "not_found", "no such file: " + rel);
            }
            byte[] body = in.readAllBytes();
            int dot = rel.lastIndexOf('.');
            String ext = dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(Locale.ROOT);
            return HttpResponse.bytes(200, TYPES.getOrDefault(ext, "application/octet-stream"), body);
        } catch (IOException e) {
            return ApiErrors.error(500, "internal", "could not read " + rel);
        }
    }

    /** True if the viewer was packaged into the jar. */
    static boolean available() {
        return StaticFiles.class.getResource(ROOT + "index.html") != null;
    }
}

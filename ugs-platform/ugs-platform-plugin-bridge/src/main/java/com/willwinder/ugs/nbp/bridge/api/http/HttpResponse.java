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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP response with a complete body.
 */
public record HttpResponse(int status, String contentType, byte[] body, Map<String, String> headers) {
    public static final String JSON = "application/json; charset=utf-8";

    public static HttpResponse json(int status, String json) {
        return new HttpResponse(status, JSON, json.getBytes(StandardCharsets.UTF_8), new LinkedHashMap<>());
    }

    public static HttpResponse text(int status, String contentType, String text) {
        return new HttpResponse(status, contentType, text.getBytes(StandardCharsets.UTF_8), new LinkedHashMap<>());
    }

    public static HttpResponse bytes(int status, String contentType, byte[] body) {
        return new HttpResponse(status, contentType, body, new LinkedHashMap<>());
    }

    public HttpResponse withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new HttpResponse(status, contentType, body, h);
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 411 -> "Length Required";
            case 413 -> "Payload Too Large";
            case 423 -> "Locked";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 501 -> "Not Implemented";
            default -> status < 400 ? "OK" : (status < 500 ? "Client Error" : "Server Error");
        };
    }
}

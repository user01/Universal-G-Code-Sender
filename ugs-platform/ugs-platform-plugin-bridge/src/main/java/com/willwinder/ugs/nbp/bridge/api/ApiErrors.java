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

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.api.http.HttpResponse;

/**
 * Error bodies (api.md "Errors"): {@code {"error": code, "message": text, "required"?: level}}.
 */
final class ApiErrors {
    static final String LOCKED_MESSAGE = "UGS access is locked by the operator";

    private ApiErrors() {
    }

    static HttpResponse error(int status, String code, String message) {
        return error(status, code, message, null);
    }

    static HttpResponse error(int status, String code, String message, Level required) {
        JsonObject o = new JsonObject();
        o.addProperty("error", code);
        o.addProperty("message", message);
        if (required != null) {
            o.addProperty("required", required.id());
        }
        return HttpResponse.json(status, o.toString());
    }

    static HttpResponse badRequest(String message) {
        return error(400, "invalid_request", message);
    }

    static HttpResponse unauthorized() {
        return error(401, "unauthorized", "missing or wrong token");
    }

    static HttpResponse locked() {
        return error(423, "locked_by_operator", LOCKED_MESSAGE);
    }

    static HttpResponse forbidden(Level required, String message) {
        return error(403, "authority_required", message, required);
    }

    static HttpResponse notFound(String message) {
        return error(404, "not_found", message);
    }

    static HttpResponse conflict(String code, String message) {
        return error(409, code, message);
    }
}

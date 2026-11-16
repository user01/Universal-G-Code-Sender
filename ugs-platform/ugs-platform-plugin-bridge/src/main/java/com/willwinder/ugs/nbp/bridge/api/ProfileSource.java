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

import com.willwinder.ugs.nbp.bridge.job.MachineProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The active machine profile. An override file {@code <bridge home>/profile.json} wins (the operator's measured
 * values); otherwise the copy of {@code bridge/profiles/shapeoko4-standard.json} bundled into the module jar at
 * build time. Re-read on every call, so edits to the override apply without restarting UGS.
 */
public final class ProfileSource {
    public static final String OVERRIDE_FILE = "profile.json";
    public static final String BUNDLED = "/com/willwinder/ugs/nbp/bridge/profiles/shapeoko4-standard.json";

    private final Path home;

    public ProfileSource(Path home) {
        this.home = home;
    }

    /** Where the profile comes from: the override path or {@code bundled:<name>}. */
    public String origin() {
        Path o = home.resolve(OVERRIDE_FILE);
        return Files.isRegularFile(o) ? o.toString() : "bundled:shapeoko4-standard.json";
    }

    /** The profile JSON text, or null if neither the override nor the bundled copy is readable. */
    public String json() {
        Path o = home.resolve(OVERRIDE_FILE);
        if (Files.isRegularFile(o)) {
            try {
                return Files.readString(o, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // fall back to the bundled copy
            }
        }
        try (InputStream in = ProfileSource.class.getResourceAsStream(BUNDLED)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public MachineProfile model() {
        String j = json();
        return j == null ? null : MachineProfile.fromJson(j);
    }
}

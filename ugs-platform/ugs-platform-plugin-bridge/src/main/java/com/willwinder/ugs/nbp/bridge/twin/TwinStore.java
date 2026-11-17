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
package com.willwinder.ugs.nbp.bridge.twin;

import com.willwinder.ugs.nbp.bridge.job.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * {@code <bridge home>/twin.json}: the operator's last stock confirmation (D27). Its own file, written only by
 * {@link ReadinessService}: {@code runtime.json} is rewritten whole by the job runtime, so a second writer would
 * lose updates.
 */
public class TwinStore {
    public static final String FORMAT = "ugs-bridge-twin/1";
    public static final String FILE = "twin.json";

    public String format = FORMAT;
    public Confirmation confirmation;

    /** "This file's stock sits where the twin shows it, at this G54." */
    public static class Confirmation {
        public String sha256;
        public String file;
        public double[] wco;
        public String at;
        /** {@code gui} (bridge panel or pre-run dialog) or {@code viewer}. */
        public String by;
    }

    public static TwinStore read(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                TwinStore s = Json.read(path, TwinStore.class);
                if (s != null && FORMAT.equals(s.format)) {
                    return s;
                }
            }
        } catch (IOException | RuntimeException e) {
            Logger.getLogger(TwinStore.class.getName()).warning("Ignoring unreadable " + path + ": " + e.getMessage());
        }
        return new TwinStore();
    }

    public void write(Path path) throws IOException {
        Json.writeAtomically(path, this);
    }
}

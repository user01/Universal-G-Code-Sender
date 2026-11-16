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

import com.willwinder.ugs.nbp.bridge.job.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the job runtime keeps across launches ({@code <bridge home>/runtime.json}): the tool-change option, UGS's own
 * tool-change setting from before the bridge turned it off, the BitSetter reference, the tool in the collet and
 * the journal of the last stream (for crash detection and "resume where it stopped").
 */
public class RuntimeState {
    public static final String FORMAT = "ugs-bridge-runtime/1";
    public static final String FILE = "runtime.json";

    public String format = FORMAT;
    /** The bridge's "BitSetter tool changes" option. */
    public boolean bitsetterToolChanges;
    /** UGS's {@code handleToolChanges} before the bridge turned it off; null when the bridge didn't. */
    public Boolean savedHandleToolChanges;
    public Reference reference;
    /** Tool in the collet as last set by a tool change or the reference measurement. */
    public String currentTool;
    /** Journal of the last stream. */
    public String activeJournal;

    /** The BitSetter reference (SPEC §11.4) and what it was taken with. */
    public static class Reference {
        public double ref;
        public double triggerMz;
        public double wcoZ;
        public String tool;
        public double[] bitsetterXy;
        public String created;
    }

    public static RuntimeState read(Path path) {
        try {
            if (Files.exists(path)) {
                RuntimeState s = Json.read(path, RuntimeState.class);
                if (s != null && FORMAT.equals(s.format)) {
                    return s;
                }
            }
        } catch (IOException | RuntimeException e) {
            java.util.logging.Logger.getLogger(RuntimeState.class.getName())
                    .warning("Ignoring unreadable " + path + ": " + e.getMessage());
        }
        return new RuntimeState();
    }

    public void write(Path path) throws IOException {
        Json.writeAtomically(path, this);
    }
}

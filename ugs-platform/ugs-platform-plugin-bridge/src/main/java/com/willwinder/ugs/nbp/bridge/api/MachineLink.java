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

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What the API needs from UGS. {@link BackendMachineLink} implements it over {@code BackendAPI}; tests use a fake.
 */
public interface MachineLink {

    /** Outcome of one line: {@code ok}, {@code error}, {@code alarm} or {@code skipped}. */
    record LineResult(String state, List<String> response, Integer errorCode, Integer alarmCode) {
        public boolean ok() {
            return "ok".equals(state);
        }
    }

    boolean isConnected();

    /** A file stream is running or paused. */
    boolean isStreaming();

    /**
     * Queues one line through UGS's own command queue ({@code sendGcodeCommand(GcodeCommand)}), tagged with the
     * origin (console label) and registered with the capture under {@code cmdId}. Completes when UGS reports the
     * command done or skipped, or exceptionally if it couldn't be queued.
     */
    CompletableFuture<LineResult> send(String line, String cmdId, String src, String tag);

    /**
     * Real-time operation: {@code hold}, {@code resume}, {@code jog_cancel}, {@code reset}, {@code status}.
     *
     * @return the byte sent, e.g. {@code 0x21}
     */
    String realtime(String op, String src) throws Exception;

    File loadedFile();

    void loadFile(File file) throws Exception;

    /** Starts streaming the loaded file, attributed to {@code src}. */
    void startStream(String src) throws Exception;

    /** Shows an INFO line in the UGS console. */
    void consoleMessage(String text);

    /** Completes every line still waiting for a reply (controller reset, disconnect) with an error result. */
    default void abandonPending(String reason) {
    }

    static String realtimeHex(String op) {
        return switch (op) {
            case "hold" -> "0x21";
            case "resume" -> "0x7e";
            case "jog_cancel" -> "0x85";
            case "reset" -> "0x18";
            case "status" -> "0x3f";
            default -> throw new IllegalArgumentException(op);
        };
    }
}

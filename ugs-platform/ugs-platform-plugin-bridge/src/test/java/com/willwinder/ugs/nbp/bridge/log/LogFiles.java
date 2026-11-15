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
package com.willwinder.ugs.nbp.bridge.log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/** Test helpers for reading session logs. */
public final class LogFiles {
    private LogFiles() {
    }

    /** Test factory for a log with a controllable clock and queue size. */
    public static SessionLog newLog(Path home, Clock clock, LongSupplier nanos, int queueCapacity) {
        return new SessionLog(home, clock, nanos, queueCapacity, SessionLog.DEFAULT_RING_CAPACITY);
    }

    /** All session.jsonl files under the logs root, sorted by path. */
    public static List<Path> sessionFiles(Path home) {
        Path root = home.resolve("logs");
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equals(SessionLog.LOG_FILE)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<JsonObject> read(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .filter(l -> !l.isBlank())
                    .map(l -> JsonParser.parseString(l).getAsJsonObject())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records of all session files, in seq order. */
    public static List<JsonObject> readAll(Path home) {
        return sessionFiles(home).stream()
                .flatMap(f -> read(f).stream())
                .sorted((a, b) -> Long.compare(a.get("seq").getAsLong(), b.get("seq").getAsLong()))
                .toList();
    }
}

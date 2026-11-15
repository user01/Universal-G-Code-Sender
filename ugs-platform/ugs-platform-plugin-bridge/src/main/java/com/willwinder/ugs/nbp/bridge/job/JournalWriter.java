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
package com.willwinder.ugs.nbp.bridge.job;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Throttled journal writer: {@link #update} writes at most once per {@code minIntervalMs}, except that a change of
 * stream state (stop, alarm, completed...) is written immediately. {@link #flush} forces a write. Not thread-safe;
 * call from one thread (the stream listener).
 */
public class JournalWriter {
    private final Path path;
    private final Clock clock;
    private final long minIntervalMs;
    private JobState pending;
    private String lastStream;
    private long lastWriteMs = Long.MIN_VALUE;

    public JournalWriter(Path path, Clock clock, long minIntervalMs) {
        this.path = Objects.requireNonNull(path);
        this.clock = Objects.requireNonNull(clock);
        this.minIntervalMs = minIntervalMs;
    }

    public Path path() {
        return path;
    }

    /** @return true if the state was written now */
    public boolean update(JobState state) throws IOException {
        pending = state;
        long now = clock.millis();
        boolean streamChanged = !Objects.equals(lastStream, state.stream);
        if (streamChanged || lastWriteMs == Long.MIN_VALUE || now - lastWriteMs >= minIntervalMs) {
            return flush();
        }
        return false;
    }

    /** @return true if there was something to write */
    public boolean flush() throws IOException {
        if (pending == null) {
            return false;
        }
        pending.touch(Instant.ofEpochMilli(clock.millis()));
        pending.write(path);
        lastStream = pending.stream;
        lastWriteMs = clock.millis();
        pending = null;
        return true;
    }
}

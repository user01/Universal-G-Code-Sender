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
package com.willwinder.ugs.nbp.bridge.capture;

import com.willwinder.universalgcodesender.types.GcodeCommand;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hook for attributing commands that the bridge itself queues (the API package, later).
 * Before queueing a {@link GcodeCommand} through UGS, the API registers it here with its bridge command id
 * ({@code c-<request>-<index>}) and source ({@code claude} or {@code bridge}). Capture then uses that id
 * and source on the matching {@code tx}, {@code rx} and {@code command} records instead of the default
 * {@code u-<GcodeCommand.getId()>} / operator-or-ugs attribution. Entries are removed when the command
 * completes or is skipped.
 */
public final class CommandOrigins {
    /** Attribution of one bridge-originated command. */
    public record Origin(String cmdId, String src, String tag) {
    }

    private final Map<Integer, Origin> byGcodeId = new ConcurrentHashMap<>();

    public void register(GcodeCommand command, String cmdId, String src, String tag) {
        byGcodeId.put(command.getId(), new Origin(cmdId, src, tag));
    }

    public Optional<Origin> lookup(GcodeCommand command) {
        return Optional.ofNullable(byGcodeId.get(command.getId()));
    }

    void forget(GcodeCommand command) {
        byGcodeId.remove(command.getId());
    }

    int size() {
        return byGcodeId.size();
    }
}

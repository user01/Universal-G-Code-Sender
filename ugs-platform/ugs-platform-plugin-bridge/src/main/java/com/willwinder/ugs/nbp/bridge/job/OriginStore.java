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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Origin store ({@code origins.json}, {@code bridge/schema/origins.schema.json}, SPEC §11.3). The path is passed
 * in by the caller; nothing here knows where Application Support is.
 */
public class OriginStore {
    public static final String FORMAT = "ugs-bridge-origins/1";

    public String format = FORMAT;
    public List<Origin> origins = new ArrayList<>();

    public static class Origin {
        public String id;
        public String name;
        public String wcs = "G54";
        public double[] wco;
        /** bitzero_xyz, bitzero_z, manual, restored, g10, observed */
        public String method;
        public String tool;
        public Double bitsetterRef;
        public String created;
        public String session;
        public String jobSha256;
        public List<String> pinnedTo;
        public String machineProfile;
        public String note;

        public Origin() {
        }

        public Origin(String id, String wcs, double[] wco, String method, Instant created) {
            this.id = id;
            this.wcs = wcs;
            this.wco = wco;
            this.method = method;
            this.created = created.toString();
        }

        /** XY (and optionally Z) of {@code otherWco} within {@code tol} mm of this origin. */
        public boolean matches(double[] otherWco, double tol, boolean compareZ) {
            return ResumePlanner.wcoMatches(wco, otherWco, tol, compareZ);
        }
    }

    /** Reads the store, or returns an empty one when the file doesn't exist. */
    public static OriginStore read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new OriginStore();
        }
        OriginStore s = Json.read(path, OriginStore.class);
        if (s == null || !FORMAT.equals(s.format)) {
            throw new IOException("Not a " + FORMAT + " file: " + path);
        }
        if (s.origins == null) {
            s.origins = new ArrayList<>();
        }
        return s;
    }

    public void write(Path path) throws IOException {
        Json.writeAtomically(path, this);
    }

    public static String newId(Instant now) {
        return "o-" + now.toEpochMilli();
    }

    public void add(Origin o) {
        if (find(o.id).isPresent()) {
            throw new IllegalArgumentException("duplicate origin id " + o.id);
        }
        origins.add(o);
    }

    public Optional<Origin> find(String id) {
        return origins.stream().filter(o -> o.id.equals(id)).findFirst();
    }

    /** The most recently created origin pinned to the job file with this SHA-256. */
    public Optional<Origin> pinnedFor(String jobSha256) {
        return origins.stream()
                .filter(o -> o.pinnedTo != null && o.pinnedTo.contains(jobSha256))
                .max(Comparator.comparing((Origin o) -> Instant.parse(o.created)));
    }

    public Optional<Origin> latest() {
        return origins.stream().max(Comparator.comparing((Origin o) -> Instant.parse(o.created)));
    }

    public void pin(String id, String jobSha256) {
        Origin o = find(id).orElseThrow(() -> new IllegalArgumentException("no origin " + id));
        if (o.pinnedTo == null) {
            o.pinnedTo = new ArrayList<>();
        }
        if (!o.pinnedTo.contains(jobSha256)) {
            o.pinnedTo.add(jobSha256);
        }
    }
}

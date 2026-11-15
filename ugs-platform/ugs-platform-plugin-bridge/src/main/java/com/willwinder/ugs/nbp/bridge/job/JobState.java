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
import java.time.Instant;
import java.util.Arrays;

/**
 * The job journal ({@code bridge/schema/job-state.schema.json}, SPEC §11.6). Line numbers are 1-based file lines.
 * Positions are millimetres: {@code lastMpos} in machine coordinates, {@code wco} the work coordinate offset
 * (work = machine − wco).
 */
public class JobState {
    public static final String FORMAT = "ugs-bridge-job-state/1";

    public String format = FORMAT;
    public String updated;
    public FileRef file;
    /** running, paused, stopped, alarm, completed, crashed */
    public String stream;
    public int lineAcked;
    public int lineDone;
    public double[] lastMpos;
    public String operation;
    public String tool;
    public double[] wco;
    public Double bitsetterRef;
    public String originId;
    public ModalState modal;
    public String session;

    public static class FileRef {
        public String path;
        public String sha256;
        public int lines;

        public FileRef() {
        }

        public FileRef(String path, String sha256, int lines) {
            this.path = path;
            this.sha256 = sha256;
            this.lines = lines;
        }
    }

    public static class ModalState {
        public String units;
        public String distance;
        public String plane;
        public String wcs;
        public String feedMode;
        public String motion;
        public Double feed;
        public String spindle;
    }

    /** A fresh journal for {@code job}, stream state {@code running}, nothing acknowledged yet. */
    public static JobState start(JobModel job, String session, Instant now) {
        JobState s = new JobState();
        s.updated = now.toString();
        s.file = new FileRef(job.path() == null ? job.name() : job.path().toString(), job.sha256(), job.lineCount());
        s.stream = "running";
        s.session = session;
        return s;
    }

    public JobState copy() {
        JobState c = Json.fromJson(Json.toJson(this), JobState.class);
        return c;
    }

    public void touch(Instant now) {
        updated = now.toString();
    }

    @Override
    public String toString() {
        return "JobState{" + stream + " acked=" + lineAcked + " done=" + lineDone
                + " mpos=" + Arrays.toString(lastMpos) + " wco=" + Arrays.toString(wco) + "}";
    }

    /** File name of the journal for a job file: {@code job-<sha>.state.json} (SPEC §11.6). */
    public static String fileName(String sha256) {
        return "job-" + sha256 + ".state.json";
    }

    public static Path pathIn(Path sessionDir, String sha256) {
        return sessionDir.resolve(fileName(sha256));
    }

    public static JobState read(Path path) throws IOException {
        JobState s = Json.read(path, JobState.class);
        if (s == null || !FORMAT.equals(s.format)) {
            throw new IOException("Not a " + FORMAT + " file: " + path);
        }
        return s;
    }

    /** Atomic write (temp file + rename). */
    public void write(Path path) throws IOException {
        Json.writeAtomically(path, this);
    }
}

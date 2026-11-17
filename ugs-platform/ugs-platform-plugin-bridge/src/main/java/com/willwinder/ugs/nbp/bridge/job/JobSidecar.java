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
import java.util.List;
import java.util.Map;

/**
 * Job sidecar {@code <program>.job.json} ({@code bridge/schema/job-sidecar.schema.json}, SPEC §6.3). Only the
 * fields the resume logic needs are modelled; the rest is ignored.
 */
public class JobSidecar {
    public static final String FORMAT = "ugs-bridge-job/1";

    public String format;
    public String units;
    public Map<String, Object> zero;
    public Double clearanceZWork;
    public Double spoilboardLimitZ;
    public List<Tool> tools;
    public List<Op> operations;
    /** Stock box: {@code size} [x, y, z] in {@link #units}, {@code origin} like "front-left-top". */
    public Stock stock;

    public static class Stock {
        public double[] size;
        public String origin;
        public String material;
    }

    public static class Tool {
        public String id;
        public String name;
        public String type;
        public Double diameter;
        public Double feed;
        public Double plunge;
    }

    public static class Op {
        public String name;
        public String tool;
        public int[] lines;
        public double[] zRange;
        public Double plungeFeed;
    }

    /**
     * Finds the sidecar for a G-code file: {@code foo.job.json} next to {@code foo.nc}, else {@code foo.nc.job.json}.
     */
    public static Path locate(Path gcode) {
        String file = gcode.getFileName().toString();
        int dot = file.lastIndexOf('.');
        Path dir = gcode.toAbsolutePath().getParent();
        if (dot > 0) {
            Path p = dir.resolve(file.substring(0, dot) + ".job.json");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        Path p = dir.resolve(file + ".job.json");
        return Files.isRegularFile(p) ? p : null;
    }

    public static JobSidecar read(Path path) throws IOException {
        JobSidecar s = Json.read(path, JobSidecar.class);
        if (s == null || !FORMAT.equals(s.format)) {
            throw new IOException("Not a " + FORMAT + " sidecar: " + path);
        }
        return s;
    }

    public static JobSidecar fromJson(String json) {
        return Json.fromJson(json, JobSidecar.class);
    }
}

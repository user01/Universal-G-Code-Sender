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

/**
 * Machine profile ({@code bridge/schema/profile.schema.json}, SPEC §7, §11.2). Only the fields the job package
 * uses are modelled; unknown fields are ignored on read. All values are millimetres and machine coordinates.
 */
public class MachineProfile {
    public String format = "ugs-bridge-profile/1";
    public String name;
    public String controller;
    public Envelope envelopeMm;
    public String homingCorner;
    public Double safeZMachine;
    public double[] toolChangePark;
    public BitSetterConfig bitsetter;
    public BitZeroConfig bitzero;
    public Spindle spindle;
    public Boolean connectResetsController;

    public static class Envelope {
        public double[] min;
        public double[] max;
        public boolean verified;

        public Envelope() {
        }

        public Envelope(double[] min, double[] max, boolean verified) {
            this.min = min;
            this.max = max;
            this.verified = verified;
        }

        /** True when the machine position (mm) lies inside the envelope, with {@code eps} slack. */
        public boolean contains(double x, double y, double z, double eps) {
            return inRange(x, 0, eps) && inRange(y, 1, eps) && inRange(z, 2, eps);
        }

        public boolean inRange(double v, int axis, double eps) {
            if (Double.isNaN(v)) {
                return true; // axis not moved
            }
            return v >= min[axis] - eps && v <= max[axis] + eps;
        }
    }

    public static class BitSetterConfig {
        public double[] xy;
        public Double startZMachine;
        public Double maxTravel;
        public Double fastFeed;
        public Double slowFeed;
        public Double retract;
        public Double repeatTolerance;
        public String pin;
        public Boolean verified;
    }

    public static class BitZeroConfig {
        public Double plateThickness;
        public Boolean plateThicknessVerified;
        public Double xyOffset;
        public Double maxTravel;
        public Double fastFeed;
        public Double slowFeed;
        public Double retract;
        public String pin;
    }

    public static class Spindle {
        public String control;
        public Double spinUpS;
    }

    public static MachineProfile read(Path path) throws IOException {
        return Json.read(path, MachineProfile.class);
    }

    public static MachineProfile fromJson(String json) {
        return Json.fromJson(json, MachineProfile.class);
    }

    public double safeZ() {
        return safeZMachine == null ? -5.0 : safeZMachine;
    }

    public double spinUpSeconds() {
        return spindle == null || spindle.spinUpS == null ? 0 : spindle.spinUpS;
    }

    public double repeatTolerance() {
        return bitsetter == null || bitsetter.repeatTolerance == null ? 0.05 : bitsetter.repeatTolerance;
    }
}

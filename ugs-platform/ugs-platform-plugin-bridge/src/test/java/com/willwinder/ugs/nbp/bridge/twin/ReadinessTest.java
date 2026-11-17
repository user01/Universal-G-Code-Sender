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

import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.runtime.RuntimeState;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Each D27 readiness check on hand-built facts. */
public class ReadinessTest {
    private static final String VERIFIED_PROFILE = """
            {"name":"t","envelope_mm":{"min":[-445,-456,-95],"max":[0,0,0],"verified":true},
             "bitsetter":{"xy":[-4.2,-448.2],"verified":true},
             "bitzero":{"plate_thickness":13.35,"plate_thickness_verified":true,"bore_center_to_corner":[1,1]},
             "spindle":{"spin_down_s":8,"spin_down_source":"timed 2026-11-20"},
             "expected_settings":{"$130":445,"$131":456,"$132":95},
             "motion":{"max_rate_mm_min":[10000,10000,1000],"accel_mm_s2":[500,500,270],"junction_deviation_mm":0.02,"arc_tolerance_mm":0.01},
             "spoilboard_z_machine":-80,
             "bed":{"verified":true,"spoilboard":{"min_xy":[-460,-432],"max_xy":[15,15]},"bitsetter_radius":20}}
            """;
    private static final String PROGRAM = """
            (STOCK: 100 x 80 x 10 mm pine)
            (ZERO: X0 Y0 = stock FRONT-LEFT corner, Z0 = stock TOP)
            (TOOL [FIRST]: T02 2-flute downcut spiral 1/4 in   feed 1500 / plunge 400 mm/min)
            G21 G90
            T2 M6
            G0 Z5
            G0 X10 Y10
            G1 Z-2 F300
            G1 X90 F1000
            G0 Z5
            """;

    private TwinProfile profile;
    private JobModel job;
    private StockSpec stock;
    private final double[] wco = {-300, -300, -60};
    private Map<String, Double> settings;
    private boolean connected;
    private boolean homed;
    private RuntimeState.Reference reference;
    private List<OriginStore.Origin> origins;
    private TwinStore.Confirmation confirmation;
    private String sha;

    @Before
    public void setUp() {
        profile = TwinProfile.parse(VERIFIED_PROFILE);
        job = JobModel.parse("t.nc", PROGRAM, null);
        stock = StockSpec.of(job, null);
        sha = job.sha256();
        settings = new LinkedHashMap<>(Map.of("$110", 10000.0, "$111", 10000.0, "$112", 1000.0, "$120", 500.0,
                "$121", 500.0, "$122", 270.0, "$11", 0.02, "$12", 0.01, "$130", 445.0, "$20", 0.0));
        settings.put("$131", 456.0);
        settings.put("$132", 95.0);
        connected = true;
        homed = true;
        reference = new RuntimeState.Reference();
        reference.ref = 22.405;
        reference.wcoZ = -60;
        reference.created = "2026-11-16T10:00:00Z";
        origins = new ArrayList<>(List.of(origin("2026-11-16T09:50:00Z", "bitzero_z", -300, -300, -60, null)));
        confirmation = confirm("2026-11-16T10:05:00Z", -300, -300, -60);
    }

    private OriginStore.Origin origin(String at, String method, double x, double y, double z, String note) {
        OriginStore.Origin o = new OriginStore.Origin("o-" + at, "G54", new double[]{x, y, z}, method, Instant.parse(at));
        o.note = note;
        return o;
    }

    private TwinStore.Confirmation confirm(String at, double x, double y, double z) {
        TwinStore.Confirmation c = new TwinStore.Confirmation();
        c.sha256 = job.sha256();
        c.wco = new double[]{x, y, z};
        c.at = at;
        c.by = "gui";
        return c;
    }

    private Readiness.Report eval() {
        return Readiness.evaluate(new Readiness.Facts(profile, settings, connected, homed, connected ? wco : null, sha,
                sha == null ? null : job, sha == null ? null : stock, reference, true, origins, confirmation));
    }

    private static Readiness.Item item(Readiness.Report r, String id) {
        return r.items().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    public void everythingMeasuredConfirmedAndInSyncIsManaged() {
        Readiness.Report r = eval();
        assertTrue(r.items().toString(), r.items().isEmpty());
        assertTrue(r.managed());
        assertEquals("managed", r.summary());
        assertEquals("managed", r.summaryJson().get("state").getAsString());
    }

    @Test
    public void assumedProfileValuesAreListedWithTheirLedgerRows() throws Exception {
        profile = TwinProfile.parse(Files.readString(PlacementContractTest.repoRoot().resolve("bridge/profiles/shapeoko4-standard.json")));
        Readiness.Item i = item(eval(), "machine_config_assumed");
        assertNotNull(i);
        assertEquals(Readiness.Severity.WARN, i.severity());
        assertTrue(i.message(), i.message().contains("machine envelope"));
        assertTrue(i.message(), i.message().contains("bed layout"));
        assertTrue(i.message(), i.message().contains("BitZero plate thickness"));
        assertTrue(i.message(), i.message().contains("router spin-down time"));
        assertTrue(i.message(), i.message().contains("spoilboard height"));
        // the BitSetter position is confirmed (front-right, 2026-11-16): only its probe settings are still assumed
        assertFalse(i.message(), i.message().contains("BitSetter position"));
        assertTrue(i.message(), i.message().contains("BitSetter probe settings"));
        assertTrue(i.fix(), i.fix().contains("B11, B12"));
        assertTrue(i.fix(), i.fix().contains("C8"));
    }

    @Test
    public void unreadableProfileIsAWarning() {
        profile = null;
        assertNotNull(item(eval(), "machine_config_missing"));
    }

    @Test
    public void controllerSettingsThatDifferAreNamed() {
        settings.put("$110", 8000.0);
        Readiness.Item i = item(eval(), "controller_settings_differ");
        assertNotNull(i);
        assertTrue(i.message(), i.message().contains("$110 is 8000 on the controller, 10000 in the profile"));
    }

    @Test
    public void maxTravelWithSoftLimitsOffIsOnlyInfo() {
        settings.put("$130", 845.0);
        Readiness.Report r = eval();
        assertNull(item(r, "controller_settings_differ"));
        assertEquals(Readiness.Severity.INFO, item(r, "controller_travel_differs").severity());
        assertTrue(r.managed());
        settings.put("$20", 1.0); // soft limits on: travel matters
        assertNotNull(item(eval(), "controller_settings_differ"));
    }

    @Test
    public void unreadSettingsAreInfo() {
        settings.clear();
        assertEquals(Readiness.Severity.INFO, item(eval(), "controller_settings_unread").severity());
    }

    @Test
    public void notHomedWarnsAndDisconnectedIsInfo() {
        homed = false;
        assertEquals(Readiness.Severity.WARN, item(eval(), "not_homed").severity());
        connected = false;
        Readiness.Report r = eval();
        assertNull(item(r, "not_homed"));
        assertEquals(Readiness.Severity.INFO, item(r, "not_connected").severity());
        assertEquals(Readiness.Severity.INFO, item(r, "placement_unchecked").severity());
    }

    @Test
    public void fileWithoutStockWarns() {
        job = JobModel.parse("t.nc", "G21 G90\nG0 X1\nG1 Z-1 F100\n", null);
        stock = StockSpec.of(job, null);
        sha = job.sha256();
        assertNull(stock);
        Readiness.Item i = item(eval(), "no_stock");
        assertNotNull(i);
        assertTrue(i.message().contains("can't show the stock or check the toolpath against it"));
        sha = null;
        assertNull(item(eval(), "no_stock"));
    }

    @Test
    public void stockNotConfirmedForThisFileOrAfterG54Moved() {
        confirmation = null;
        assertNotNull(item(eval(), "stock_not_confirmed"));
        confirmation = confirm("2026-11-16T10:05:00Z", -300, -300, -60);
        confirmation.sha256 = "other";
        assertNotNull(item(eval(), "stock_not_confirmed"));
        confirmation = confirm("2026-11-16T10:05:00Z", -250, -300, -60);
        Readiness.Item i = item(eval(), "stock_not_confirmed");
        assertTrue(i.message(), i.message().contains("X/Y moved"));
    }

    @Test
    public void bitSetterToolChangeKeepsTheConfirmationButAZeroDoesNot() {
        origins.add(origin("2026-11-16T10:10:00Z", "g10", -300, -300, -56.165,
                "BitSetter tool change to T21: S=-69.650, ref=22.405"));
        wco[2] = -56.165;
        assertNull(eval().items().toString(), item(eval(), "stock_not_confirmed"));
        assertNull(item(eval(), "bitsetter_reference_stale"));
        origins.add(origin("2026-11-16T10:20:00Z", "bitzero_z", -300, -300, -57.0, null));
        wco[2] = -57.0;
        Readiness.Report r = eval();
        assertTrue(item(r, "stock_not_confirmed").message().contains("Z changed (bitzero_z)"));
        assertTrue(item(r, "bitsetter_reference_stale").message().contains("bitzero_z"));
    }

    @Test
    public void g54ChangedOutsideTheBridgeUntilTheOperatorConfirms() {
        origins.add(origin("2026-11-16T11:00:00Z", "observed", -345.05, -314, -47.245,
                "first reading after connect ($#) differs from the store"));
        Readiness.Item i = item(eval(), "g54_changed_outside");
        assertNotNull(i);
        assertTrue(i.message(), i.message().contains("since the last session"));
        assertTrue(i.fix(), i.fix().contains("re-take the BitSetter reference"));
        confirmation = confirm("2026-11-16T11:05:00Z", -345.05, -314, -47.245);
        assertNull(item(eval(), "g54_changed_outside"));
        origins.add(origin("2026-11-16T11:10:00Z", "bitzero_z", -345.05, -314, -48, null));
        confirmation = null;
        assertNull(item(eval(), "g54_changed_outside")); // the newest change was the operator's own zero
    }

    @Test
    public void referenceStaleAfterAnUnmanagedZChangeButNotAfterRestoringWithTheSameReference() {
        OriginStore.Origin restored = origin("2026-11-16T10:30:00Z", "restored", -300, -300, -61, null);
        restored.bitsetterRef = 22.405;
        origins.add(restored);
        assertNull(item(eval(), "bitsetter_reference_stale"));
        OriginStore.Origin other = origin("2026-11-16T10:40:00Z", "restored", -300, -300, -62, null);
        other.bitsetterRef = 20.0;
        origins.add(other);
        assertNotNull(item(eval(), "bitsetter_reference_stale"));
    }

    @Test
    public void missingReferenceWarnsOnlyWhenTheProgramChangesTools() {
        reference = null;
        assertNotNull(item(eval(), "bitsetter_reference_missing"));
        job = JobModel.parse("t.nc", PROGRAM.replace("T2 M6\n", ""), null);
        stock = StockSpec.of(job, null);
        sha = job.sha256();
        confirmation = confirm("2026-11-16T10:05:00Z", -300, -300, -60);
        assertNull(item(eval(), "bitsetter_reference_missing"));
    }

    @Test
    public void placementFindingsBecomeWarnings() {
        wco[0] = -60; // stock X -60..40: past the spoilboard (max 15) and the envelope
        confirmation = confirm("2026-11-16T10:05:00Z", -60, -300, -60);
        Readiness.Report r = eval();
        assertNotNull(r.items().toString(), item(r, "stock_off_spoilboard"));
        assertNotNull(item(r, "outside_envelope"));
        assertEquals(2, r.warnings().size());
        assertTrue(r.summary(), r.summary().startsWith("2 warnings ("));
    }
}

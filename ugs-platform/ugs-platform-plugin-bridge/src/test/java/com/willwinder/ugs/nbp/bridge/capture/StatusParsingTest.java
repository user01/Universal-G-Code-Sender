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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.EnabledPinsBuilder;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatusParsingTest {
    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    public void parsesFullGrbl11Report() {
        GrblStatusParser p = new GrblStatusParser();
        String raw = "<Hold:0|MPos:-10.000,-20.500,-1.250|Bf:15,127|Ln:42|FS:500,12000|WCO:-5.000,-10.000,-2.000|Ov:100,90,110|Pn:PZ|A:SFM>";
        JsonObject o = p.parse(raw);
        assertEquals("Hold", o.get("state").getAsString());
        assertEquals(0, o.get("substate").getAsInt());
        assertEquals(json("[-10.0,-20.5,-1.25]"), o.get("mpos"));
        assertEquals(json("[-5.0,-10.5,0.75]"), o.get("wpos"));
        assertEquals(json("[-5.0,-10.0,-2.0]"), o.get("wco"));
        assertEquals(500, o.get("feed").getAsDouble(), 0);
        assertEquals(12000, o.get("spindle").getAsDouble(), 0);
        assertEquals(json("{\"planner\":15,\"rx\":127}"), o.get("buffer"));
        assertEquals(42, o.get("line").getAsInt());
        assertEquals(json("{\"feed\":100,\"rapid\":90,\"spindle\":110}"), o.get("overrides"));
        JsonObject pins = o.getAsJsonObject("pins");
        assertEquals("PZ", pins.get("raw").getAsString());
        assertTrue(pins.get("probe").getAsBoolean());
        assertTrue(pins.get("z_limit").getAsBoolean());
        assertFalse(pins.get("x_limit").getAsBoolean());
        JsonObject acc = o.getAsJsonObject("accessories");
        assertTrue(acc.get("spindle_cw").getAsBoolean());
        assertFalse(acc.get("spindle_ccw").getAsBoolean());
        assertTrue(acc.get("flood").getAsBoolean());
        assertTrue(acc.get("mist").getAsBoolean());
        assertEquals(raw, o.get("raw").getAsString());
    }

    @Test
    public void carriesWcoOverridesAndAccessoriesAcrossReports() {
        GrblStatusParser p = new GrblStatusParser();
        p.parse("<Idle|MPos:1.000,2.000,3.000|FS:0,0|WCO:1.000,1.000,1.000|Ov:100,100,100|A:S>");
        JsonObject o = p.parse("<Run|MPos:2.000,2.000,3.000|FS:100,0>");
        assertEquals(json("[1.0,1.0,2.0]"), o.get("wpos"));
        assertEquals(json("[1.0,1.0,1.0]"), o.get("wco"));
        assertEquals(100, o.getAsJsonObject("overrides").get("feed").getAsInt());
        assertTrue(o.getAsJsonObject("accessories").get("spindle_cw").getAsBoolean());
        assertEquals("", o.getAsJsonObject("pins").get("raw").getAsString());
        assertFalse(o.getAsJsonObject("pins").get("probe").getAsBoolean());
        assertTrue(o.get("substate").isJsonNull());
        assertTrue(o.get("buffer").isJsonNull());
        assertTrue(o.get("line").isJsonNull());

        // Ov without A: all accessories off
        o = p.parse("<Idle|MPos:2.000,2.000,3.000|FS:0,0|Ov:100,100,100>");
        assertFalse(o.getAsJsonObject("accessories").get("spindle_cw").getAsBoolean());
    }

    @Test
    public void wposOnlyReportDerivesMpos() {
        GrblStatusParser p = new GrblStatusParser();
        p.parse("<Idle|WPos:0.000,0.000,0.000|WCO:-10.000,-10.000,-5.000>");
        JsonObject o = p.parse("<Idle|WPos:1.000,0.000,0.000>");
        assertEquals(json("[-9.0,-10.0,-5.0]"), o.get("mpos"));
    }

    @Test
    public void withoutWcoWposIsNull() {
        JsonObject o = new GrblStatusParser().parse("<Alarm|MPos:0.000,0.000,0.000|FS:0,0>");
        assertEquals("Alarm", o.get("state").getAsString());
        assertTrue(o.get("wpos").isJsonNull());
        assertTrue(o.get("wco").isJsonNull());
    }

    @Test
    public void rejectsNonReportsAndGrbl09() {
        GrblStatusParser p = new GrblStatusParser();
        assertNull(p.parse("ok"));
        assertNull(p.parse("<Idle,MPos:0.000,0.000,0.000,WPos:0.000,0.000,0.000>"));
        assertEquals("Unknown", p.parse("<Weird|MPos:0,0,0>").get("state").getAsString());
    }

    @Test
    public void mapsUgsControllerStatus() {
        ControllerStatus s = new ControllerStatus(ControllerState.JOG, "", new Position(1, 2, 3, UnitUtils.Units.INCH),
                new Position(Double.NaN, Double.NaN, Double.NaN, UnitUtils.Units.MM), 10d, UnitUtils.Units.MM, 0d,
                null, null, new EnabledPinsBuilder().setProbe(true).setX(true).createEnabledPins(), null);
        JsonObject o = RecordMapper.status(s);
        assertEquals("Jog", o.get("state").getAsString());
        assertEquals(json("[25.4,50.8,76.2]"), o.get("mpos"));
        assertTrue(o.get("wpos").isJsonNull());
        assertEquals("XP", o.getAsJsonObject("pins").get("raw").getAsString());
        assertEquals("", o.get("raw").getAsString());
        assertNotNull(o.get("accessories"));
        assertEquals("Unknown", RecordMapper.stateName(ControllerState.CONNECTING));
    }

    @Test
    public void codes() {
        assertEquals(Integer.valueOf(22), RecordMapper.errorCode("error:22"));
        assertEquals(Integer.valueOf(1), RecordMapper.alarmCode("ALARM:1"));
        assertNull(RecordMapper.errorCode("ok"));
        assertTrue(RecordMapper.isAck("ok"));
        assertTrue(RecordMapper.isAck("error:9"));
        assertFalse(RecordMapper.isAck("[MSG:Caution: Unlocked]"));
        assertTrue(RecordMapper.isWelcome("Grbl 1.1h ['$' for help]"));
    }

    // ---------------------------------------------------------------- de-dup + heartbeat

    private static final long MS = 1_000_000L;

    @Test
    public void unchangedReportsAreDroppedUntilHeartbeat() {
        StatusFilter f = new StatusFilter();
        GrblStatusParser p = new GrblStatusParser();
        String r = "<Idle|MPos:0.000,0.000,0.000|FS:0,0>";
        assertNotNull(f.offer(p.parse(r), 0));
        assertNull(f.offer(p.parse(r), 200 * MS));
        assertNull(f.offer(p.parse(r), 400 * MS));
        assertNull(f.tick(900 * MS));
        JsonObject hb = f.tick(1000 * MS);
        assertNotNull(hb);
        assertTrue(hb.get("heartbeat").getAsBoolean());
        // nothing new arrived since the heartbeat: no further heartbeat
        assertNull(f.tick(2500 * MS));
        // a report arriving more than 1 s after the last record is itself a heartbeat
        JsonObject o = f.offer(p.parse(r), 2600 * MS);
        assertNotNull(o);
        assertTrue(o.get("heartbeat").getAsBoolean());
    }

    @Test
    public void feedSpindleJitterBelowOnePercentIsIgnored() {
        StatusFilter f = new StatusFilter();
        GrblStatusParser p = new GrblStatusParser();
        assertNotNull(f.offer(p.parse("<Run|MPos:0.000,0.000,0.000|FS:1000,10000>"), 0));
        assertNull(f.offer(p.parse("<Run|MPos:0.000,0.000,0.000|FS:1009,10050>"), 10 * MS));
        assertNotNull(f.offer(p.parse("<Run|MPos:0.000,0.000,0.000|FS:1020,10000>"), 20 * MS));
        assertNotNull(f.offer(p.parse("<Run|MPos:0.000,0.000,0.000|FS:1020,12000>"), 30 * MS));
    }

    @Test
    public void anyOtherChangeIsLogged() {
        StatusFilter f = new StatusFilter();
        GrblStatusParser p = new GrblStatusParser();
        assertNotNull(f.offer(p.parse("<Idle|MPos:0.000,0.000,0.000|FS:0,0>"), 0));
        assertNotNull(f.offer(p.parse("<Idle|MPos:0.000,0.000,0.000|FS:0,0|Pn:P>"), 1));
        assertNotNull(f.offer(p.parse("<Idle|MPos:0.001,0.000,0.000|FS:0,0|Pn:P>"), 2));
        assertNotNull(f.offer(p.parse("<Hold:1|MPos:0.001,0.000,0.000|FS:0,0|Pn:P>"), 3));
        assertNull(f.offer(p.parse("<Hold:1|MPos:0.001,0.000,0.000|FS:0,0|Pn:P>"), 4));
    }
}

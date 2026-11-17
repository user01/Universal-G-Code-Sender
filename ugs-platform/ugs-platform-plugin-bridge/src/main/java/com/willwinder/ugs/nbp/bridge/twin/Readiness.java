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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.runtime.RuntimeState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Is the twin managed? D27: whenever what the twin shows is assumed, missing, or has drifted from the machine
 * without the bridge knowing, say so. Warnings never block anything; they inform the operator (bridge panel, the
 * pre-run dialog, the viewer, Telegram) and Claude (MCP). Pure: {@link #evaluate} works on {@link Facts} only.
 */
public final class Readiness {
    /** Tolerance for "the same G54", mm. */
    public static final double SAME_MM = 0.01;
    /** Settings that only set the homing search distance while soft limits are off. */
    private static final Set<String> TRAVEL = Set.of("$130", "$131", "$132");

    public enum Severity {
        INFO, WARN;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One check result: a stable id, how serious, what's wrong in plain words, and how to fix it. */
    public record Item(String id, Severity severity, String message, String fix) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("severity", severity.id());
            o.addProperty("message", message);
            o.addProperty("fix", fix);
            return o;
        }
    }

    /** All findings; {@code managed} when there is no warning. */
    public record Report(List<Item> items) {
        public List<Item> warnings() {
            return items.stream().filter(i -> i.severity() == Severity.WARN).toList();
        }

        public boolean managed() {
            return warnings().isEmpty();
        }

        public List<String> warningIds() {
            return warnings().stream().map(Item::id).toList();
        }

        /** "managed" or "3 warnings (not_homed, stock_not_confirmed, ...)". */
        public String summary() {
            List<String> ids = warningIds();
            if (ids.isEmpty()) {
                return "managed";
            }
            return ids.size() + " warning" + (ids.size() == 1 ? "" : "s") + " (" + String.join(", ", ids) + ")";
        }

        /** {@code GET /twin/readiness}. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("state", managed() ? "managed" : "warnings");
            o.addProperty("warnings", warnings().size());
            o.addProperty("infos", items.size() - warnings().size());
            JsonArray a = new JsonArray();
            items.forEach(i -> a.add(i.toJson()));
            o.add("items", a);
            return o;
        }

        /** The short form in {@code GET /status}: state, counts, warning ids. */
        public JsonObject summaryJson() {
            JsonObject o = new JsonObject();
            o.addProperty("state", managed() ? "managed" : "warnings");
            o.addProperty("warnings", warnings().size());
            o.addProperty("infos", items.size() - warnings().size());
            JsonArray ids = new JsonArray();
            warningIds().forEach(ids::add);
            o.add("ids", ids);
            return o;
        }
    }

    /**
     * Everything the checks look at.
     *
     * @param profile            the active profile (null = unreadable)
     * @param controllerSettings {@code $$} values seen this session ("$110" → 10000), empty when not read
     * @param connected          a controller is connected
     * @param homed              homed in this connection
     * @param wco                live G54 offset (mm), or null
     * @param fileSha            the loaded file's SHA-256, or null when none is loaded
     * @param job                the loaded program, or null
     * @param stock              the program's stock, or null
     * @param reference          the stored BitSetter reference, or null
     * @param toolChangesOn      BitSetter tool changes are on
     * @param origins            the origin store (every G54 change the bridge saw), any order
     * @param confirmation       the operator's last stock confirmation, or null
     */
    public record Facts(TwinProfile profile, Map<String, Double> controllerSettings, boolean connected, boolean homed,
                        double[] wco, String fileSha, JobModel job, StockSpec stock, RuntimeState.Reference reference,
                        boolean toolChangesOn, List<OriginStore.Origin> origins, TwinStore.Confirmation confirmation) {
    }

    private Readiness() {
    }

    public static Report evaluate(Facts f) {
        List<Item> out = new ArrayList<>();
        machineConfig(f, out);
        controllerSettings(f, out);
        connection(f, out);
        g54ChangedOutside(f, out);
        reference(f, out);
        stock(f, out);
        return new Report(List.copyOf(out));
    }

    // 1. profile values still assumed
    private static void machineConfig(Facts f, List<Item> out) {
        if (f.profile() == null) {
            out.add(new Item("machine_config_missing", Severity.WARN,
                    "The machine profile can't be read: the twin and the safety checks have no machine to work with.",
                    "Fix or remove <bridge home>/profile.json (the bundled Shapeoko 4 profile is used without it)."));
            return;
        }
        List<TwinProfile.Assumed> a = f.profile().assumed();
        if (a.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (TwinProfile.Assumed x : a) {
            parts.add(x.label());
            if (x.ledger() != null) {
                rows.add(x.ledger());
            }
        }
        out.add(new Item("machine_config_assumed", Severity.WARN,
                "Machine values not measured yet, so the twin shows them as assumed: " + String.join(", ", parts) + ".",
                "Measure them as the machine ledger says (bridge/docs/machine-ledger.md"
                        + (rows.isEmpty() ? "" : ", rows " + String.join(", ", rows))
                        + ") and set verified in the profile."));
    }

    // 2. controller $$ vs profile
    private static void controllerSettings(Facts f, List<Item> out) {
        if (f.profile() == null || !f.connected()) {
            return;
        }
        Map<String, Double> c = f.controllerSettings();
        if (c == null || c.isEmpty()) {
            out.add(new Item("controller_settings_unread", Severity.INFO,
                    "The controller's settings ($$) haven't been read in this session, so they can't be compared with the profile.",
                    "Send $$ in the UGS console (or let Claude read them at Query level)."));
            return;
        }
        List<String> warn = new ArrayList<>();
        List<String> info = new ArrayList<>();
        boolean softLimitsOff = c.containsKey("$20") && c.get("$20") == 0;
        for (Map.Entry<String, Double> e : f.profile().expectedSettings().entrySet()) {
            Double have = c.get(e.getKey());
            if (have == null || Math.abs(have - e.getValue()) <= 1e-6 * Math.max(1, Math.abs(e.getValue()))) {
                continue;
            }
            String d = e.getKey() + " is " + num(have) + " on the controller, " + num(e.getValue()) + " in the profile";
            if (TRAVEL.contains(e.getKey()) && softLimitsOff) {
                info.add(d);
            } else {
                warn.add(d);
            }
        }
        if (!warn.isEmpty()) {
            out.add(new Item("controller_settings_differ", Severity.WARN,
                    "The controller doesn't match the profile: " + String.join("; ", warn)
                            + ". Time estimates use the controller; the twin and checks use the profile.",
                    "Update the profile (or the controller, if it was changed by mistake)."));
        }
        if (!info.isEmpty()) {
            out.add(new Item("controller_travel_differs", Severity.INFO,
                    "Max travel differs: " + String.join("; ", info)
                            + ". Soft limits are off ($20=0), so this only sets the homing search distance.",
                    "Nothing to do unless you turn soft limits on."));
        }
    }

    // 7. homed
    private static void connection(Facts f, List<Item> out) {
        if (!f.connected()) {
            out.add(new Item("not_connected", Severity.INFO,
                    "No controller connected: the twin shows the last known state.", "Connect in UGS."));
            return;
        }
        if (!f.homed()) {
            out.add(new Item("not_homed", Severity.WARN,
                    "Not homed since connecting (or since an alarm): machine positions, and so the twin, may be off.",
                    "Home the machine ($H) in UGS."));
        }
    }

    // 5. G54 changed by something the bridge didn't see doing it
    private static void g54ChangedOutside(Facts f, List<Item> out) {
        OriginStore.Origin latest = latest(f.origins());
        if (latest == null || !"observed".equals(latest.method)) {
            return;
        }
        Instant at = instant(latest.created);
        if (f.confirmation() != null && at != null && instant(f.confirmation().at) != null
                && !instant(f.confirmation().at).isBefore(at)) {
            return; // the operator confirmed the placement after the change
        }
        out.add(new Item("g54_changed_outside", Severity.WARN,
                "G54 changed outside the bridge" + (latest.note != null && latest.note.startsWith("first reading")
                        ? " since the last session (Carbide Motion or another program?)" : "")
                        + ": now " + vec(latest.wco) + ".",
                "Re-check the work zero (X, Y and Z) and re-take the BitSetter reference, then confirm the stock."));
    }

    // 6. BitSetter reference vs later Z-zero changes
    private static void reference(Facts f, List<Item> out) {
        boolean needsIt = f.toolChangesOn() && f.job() != null && !f.job().toolChangeLines().isEmpty();
        RuntimeState.Reference r = f.reference();
        if (r == null) {
            if (needsIt) {
                out.add(new Item("bitsetter_reference_missing", Severity.WARN,
                        "The program changes tools but there is no BitSetter reference: the first tool change can't carry Z.",
                        "Machine → Measure BitSetter reference… with the tool you zeroed Z with."));
            }
            return;
        }
        OriginStore.Origin bad = unmanagedZChangeSince(f.origins(), instant(r.created), r.wcoZ, r.ref);
        if (bad != null) {
            out.add(new Item("bitsetter_reference_stale", Severity.WARN, String.format(Locale.ROOT,
                    "G54 Z changed (%s, %s) after the BitSetter reference was taken: tool changes would carry the wrong Z.",
                    bad.method, num(bad.wco[2])),
                    "Re-take the BitSetter reference (Machine → Measure BitSetter reference…) with the tool now in the collet."));
        }
    }

    /**
     * The first G54 change after {@code since} whose Z moved and that the bridge did not make itself (a BitSetter
     * tool change, or restoring an origin taken with the same reference), or null.
     */
    static OriginStore.Origin unmanagedZChangeSince(List<OriginStore.Origin> origins, Instant since, double startZ, Double ref) {
        if (since == null || origins == null) {
            return null;
        }
        double z = startZ;
        for (OriginStore.Origin o : chronological(origins)) {
            Instant at = instant(o.created);
            if (at == null || !at.isAfter(since) || o.wco == null || o.wco.length < 3) {
                continue;
            }
            if (Math.abs(o.wco[2] - z) > SAME_MM && !managedZ(o, ref)) {
                return o;
            }
            z = o.wco[2];
        }
        return null;
    }

    private static boolean managedZ(OriginStore.Origin o, Double ref) {
        if ("g10".equals(o.method) && o.note != null && o.note.startsWith("BitSetter tool change")) {
            return true;
        }
        return "restored".equals(o.method) && ref != null && o.bitsetterRef != null && Math.abs(o.bitsetterRef - ref) <= SAME_MM;
    }

    // 3, 4, 8. stock
    private static void stock(Facts f, List<Item> out) {
        if (f.fileSha() == null) {
            return;
        }
        if (f.stock() == null) {
            out.add(new Item("no_stock", Severity.WARN,
                    "The loaded file doesn't say what stock it's for (no STOCK header, no sidecar): the twin can't show "
                            + "the stock or check the toolpath against it.",
                    "Post the file with a (STOCK: x × y × z mm) header, or add a <program>.job.json sidecar with stock."));
        }
        if (f.wco() == null) {
            if (f.stock() != null) {
                out.add(new Item("placement_unchecked", Severity.INFO,
                        "The stock placement can't be checked without a live G54.", "Connect in UGS."));
            }
            return;
        }
        if (f.stock() != null) {
            String why = confirmationProblem(f);
            if (why != null) {
                out.add(new Item("stock_not_confirmed", Severity.WARN, why,
                        "Check the stock sits where the twin shows it, then press Confirm stock (bridge panel or 3D view)."));
            }
        }
        if (f.profile() != null) {
            for (Placement.Item p : Placement.check(f.profile(), f.stock(), f.job(), f.wco())) {
                out.add(new Item(p.kind(), Severity.WARN, p.message(), switch (p.kind()) {
                    case "stock_on_bitsetter" -> "Move the stock (and re-zero) clear of the BitSetter.";
                    case "stock_off_spoilboard" -> "Move the stock onto the spoilboard, or measure the spoilboard (ledger B11) if it's really fine.";
                    case "cut_outside_stock" -> "Check the stock size and the work zero against the file's ZERO line.";
                    default -> "Move the work zero, or check the profile's envelope (ledger A1).";
                }));
            }
        }
    }

    private static String confirmationProblem(Facts f) {
        TwinStore.Confirmation c = f.confirmation();
        if (c == null || c.sha256 == null || !c.sha256.equals(f.fileSha())) {
            return "You haven't confirmed where this file's stock is: the twin places it from G54 and the file's stock "
                    + "box, which nobody has checked against the machine.";
        }
        if (c.wco == null || c.wco.length < 3 || Math.abs(c.wco[0] - f.wco()[0]) > SAME_MM
                || Math.abs(c.wco[1] - f.wco()[1]) > SAME_MM) {
            return "G54 X/Y moved since you confirmed the stock (" + vec(c.wco) + " then, " + vec(f.wco()) + " now).";
        }
        OriginStore.Origin z = unmanagedZChangeSince(f.origins(), instant(c.at), c.wco[2],
                f.reference() == null ? null : f.reference().ref);
        if (z != null) {
            return "G54 Z changed (" + z.method + ") since you confirmed the stock.";
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static OriginStore.Origin latest(List<OriginStore.Origin> origins) {
        if (origins == null || origins.isEmpty()) {
            return null;
        }
        List<OriginStore.Origin> c = chronological(origins);
        return c.isEmpty() ? null : c.get(c.size() - 1);
    }

    private static List<OriginStore.Origin> chronological(List<OriginStore.Origin> origins) {
        return origins.stream().filter(o -> instant(o.created) != null)
                .sorted(Comparator.comparing((OriginStore.Origin o) -> instant(o.created))).toList();
    }

    static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String vec(double[] w) {
        if (w == null || w.length < 3) {
            return "unknown";
        }
        return String.format(Locale.ROOT, "X%.3f Y%.3f Z%.3f", w[0], w[1], w[2]);
    }
}

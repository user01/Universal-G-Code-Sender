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
package com.willwinder.ugs.nbp.bridge.ui;

import com.willwinder.ugs.nbp.bridge.twin.Readiness;
import com.willwinder.ugs.nbp.bridge.twin.ReadinessService;
import com.willwinder.ugs.nbp.core.actions.StartGuard;
import org.openide.util.lookup.ServiceProvider;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * D27 pre-run check: when the operator presses Send in UGS and the twin has warnings, list them and ask
 * "Start anyway" / "Cancel". Never blocks on its own: no warnings, no bridge, or a check that doesn't answer in
 * 3 s all start as before. The answer is logged ({@code event} type {@code twin}).
 */
@ServiceProvider(service = StartGuard.class)
public final class TwinStartGuard implements StartGuard {
    private static final Logger LOGGER = Logger.getLogger(TwinStartGuard.class.getName());

    @Override
    public boolean beforeStart(Component parent) {
        ReadinessService r = TwinRow.service();
        if (r == null) {
            return true;
        }
        Readiness.Report rep;
        try {
            rep = CompletableFuture.supplyAsync(r::report).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "readiness check before start failed", e);
            return true;
        }
        if (rep.managed()) {
            return true;
        }
        Object[] options = {"Start anyway", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, TwinRow.html(withoutInfos(rep), false),
                "Twin: " + rep.warnings().size() + " warning" + (rep.warnings().size() == 1 ? "" : "s") + " before starting",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        boolean start = choice == 0;
        r.logStartDecision(start, rep);
        return start;
    }

    private static Readiness.Report withoutInfos(Readiness.Report r) {
        return new Readiness.Report(r.warnings());
    }
}

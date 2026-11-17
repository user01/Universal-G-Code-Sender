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

import com.willwinder.ugs.nbp.bridge.BridgeService;
import com.willwinder.ugs.nbp.bridge.api.ApiHost;
import com.willwinder.ugs.nbp.bridge.twin.Readiness;
import com.willwinder.ugs.nbp.bridge.twin.ReadinessService;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.concurrent.CompletableFuture;

/**
 * The "Twin" row of the bridge panel (D27): green "managed" or amber "N warnings"; Details lists each warning with
 * its fix, Confirm stock records that the loaded file's stock sits where the twin shows it.
 */
final class TwinRow extends JPanel {
    private final JLabel label = new JLabel();
    private final JButton details = new JButton("Details…");
    private final JButton confirm = new JButton("Confirm stock");

    TwinRow() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        add(label);
        add(details);
        add(confirm);
        details.addActionListener(e -> showDetails(this));
        confirm.addActionListener(e -> confirmStock(this));
        confirm.setToolTipText("The stock of the loaded file sits where the 3D view shows it, at the current G54");
    }

    /** Called by the panel's 1 s refresh; reads the last published report only (no file access on the EDT). */
    void refresh() {
        ReadinessService r = service();
        Readiness.Report rep = r == null ? null : r.last();
        if (rep == null) {
            label.setText("<html><b>Twin</b>: <span style='color:gray'>not checked yet</span></html>");
            details.setEnabled(false);
            confirm.setEnabled(r != null);
            return;
        }
        details.setEnabled(true);
        confirm.setEnabled(true);
        if (rep.managed()) {
            label.setText("<html><b>Twin</b>: <span style='color:#1E8449'><b>managed</b></span></html>");
        } else {
            int n = rep.warnings().size();
            label.setText("<html><b>Twin</b>: <span style='color:#B9770E'><b>" + n + " warning" + (n == 1 ? "" : "s")
                    + "</b></span></html>");
        }
        boolean needsConfirm = rep.warningIds().contains("stock_not_confirmed");
        confirm.setVisible(needsConfirm || rep.warningIds().contains("g54_changed_outside"));
    }

    static ReadinessService service() {
        ApiHost api = BridgeService.api();
        return api == null ? null : api.readiness();
    }

    /** The findings as HTML for a dialog. */
    static String html(Readiness.Report r, boolean infos) {
        StringBuilder b = new StringBuilder("<html><div style='width:420px'>");
        for (Readiness.Item i : r.items()) {
            if (i.severity() == Readiness.Severity.INFO && !infos) {
                continue;
            }
            b.append(i.severity() == Readiness.Severity.WARN ? "<p><b style='color:#B9770E'>⚠ " : "<p><b style='color:gray'>ℹ ")
                    .append(escape(i.id())).append("</b><br>").append(escape(i.message()))
                    .append("<br><i>Fix:</i> ").append(escape(i.fix())).append("</p><br>");
        }
        if (r.items().isEmpty()) {
            b.append("Everything the twin shows is measured, confirmed and in sync with the machine.");
        }
        return b.append("</div></html>").toString();
    }

    private static void showDetails(Component parent) {
        ReadinessService r = service();
        if (r == null) {
            return;
        }
        CompletableFuture.supplyAsync(r::report).thenAccept(rep -> SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(parent, html(rep, true), "Twin readiness", JOptionPane.INFORMATION_MESSAGE)));
    }

    private static void confirmStock(Component parent) {
        ReadinessService r = service();
        if (r == null) {
            return;
        }
        int ok = JOptionPane.showConfirmDialog(parent,
                "<html><div style='width:360px'>Confirm that the stock of the loaded file sits where the 3D view shows it "
                        + "(work zero at the current G54, stock size from the file)?<br><br>Any later G54 X/Y change, "
                        + "or a Z change the bridge didn't make, asks again.</div></html>",
                "Confirm stock", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        CompletableFuture.runAsync(() -> r.confirm("gui")).whenComplete((v, err) -> {
            if (err != null) {
                Throwable t = err.getCause() != null ? err.getCause() : err;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent,
                        "Not confirmed: " + t.getMessage(), "Confirm stock", JOptionPane.WARNING_MESSAGE));
            }
        });
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

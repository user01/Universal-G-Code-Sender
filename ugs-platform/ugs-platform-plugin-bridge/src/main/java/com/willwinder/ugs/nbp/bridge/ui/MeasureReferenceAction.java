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

import com.willwinder.ugs.nbp.bridge.runtime.JobRuntime;
import com.willwinder.ugs.nbp.bridge.runtime.ToolChangeRoutine;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * <em>Machine → Measure BitSetter reference…</em> (SPEC §11.4): with Z already zeroed on the stock (BitZero), the
 * operator measures the tool in the collet on the BitSetter and the bridge stores {@code ref = WCO_z − S}. Every
 * later {@code M6} sets Z from that reference. Runs {@link JobRuntime#measureReference(String, boolean)} off the EDT.
 */
@ActionID(category = LocalizingService.CATEGORY_MACHINE, id = MeasureReferenceAction.ID)
@ActionRegistration(displayName = "Measure BitSetter reference…", lazy = false)
@ActionReference(path = LocalizingService.MENU_MACHINE, position = 1995)
public final class MeasureReferenceAction extends AbstractAction {
    public static final String ID = "com.willwinder.ugs.nbp.bridge.ui.MeasureReferenceAction";
    static final String TEXT = "Measure BitSetter reference…";
    static final String DEFAULT_TOOL = "T02";
    private static final Pattern TOOL = Pattern.compile("(?i)T?\\d{1,3}");
    private volatile boolean running;

    public MeasureReferenceAction() {
        putValue(NAME, TEXT);
        putValue("menuText", TEXT);
        putValue(SHORT_DESCRIPTION, "With Z zeroed on the stock (BitZero), measure the tool in the collet on the "
                + "BitSetter and store the reference for later tool changes");
    }

    @Override
    public boolean isEnabled() {
        return !running && super.isEnabled();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        JobRuntime rt = JobRuntime.current();
        if (rt == null) {
            error("The bridge isn't running.");
            return;
        }
        String notReady = notReady();
        if (notReady != null) {
            error(notReady);
            return;
        }
        String suggested = rt.suggestedReferenceTool();
        JTextField tool = new JTextField(suggested == null || suggested.isBlank() ? DEFAULT_TOOL : suggested, 6);
        JCheckBox twice = new JCheckBox("Measure twice (repeat check)", true);
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel reminder = new JLabel("<html><b>Z must already be zeroed on the stock with the BitZero</b>, with this "
                + "tool in the collet.<br>The machine moves to the BitSetter at safe Z, probes, and returns here.<br>"
                + "Keep a hand near the stop button.</html>");
        reminder.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        p.add(reminder);
        JPanel row = new JPanel();
        row.add(new JLabel("Tool in the collet:"));
        row.add(tool);
        p.add(row);
        p.add(twice);
        int ok = JOptionPane.showConfirmDialog(null, p, "Measure BitSetter reference", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        String t = normalizeTool(tool.getText());
        if (t == null) {
            error("Enter the tool as T<number>, for example T02.");
            return;
        }
        running = true;
        setEnabled(false);
        rt.measureReference(t, twice.isSelected()).whenComplete((r, ex) -> SwingUtilities.invokeLater(() -> {
            running = false;
            setEnabled(true);
            if (ex != null) {
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                error("Reference measurement failed: " + cause.getMessage());
            } else {
                JOptionPane.showMessageDialog(null, describe(r), "BitSetter reference stored",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        }));
    }

    /** {@code t2}, {@code 2}, {@code T02} → {@code T02}; null when it isn't a tool number. */
    static String normalizeTool(String text) {
        String s = text == null ? "" : text.trim();
        if (!TOOL.matcher(s).matches()) {
            return null;
        }
        int n = Integer.parseInt(s.replaceFirst("(?i)^T", ""));
        return String.format(Locale.ROOT, "T%02d", n);
    }

    static String describe(ToolChangeRoutine.Reference r) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "Tool %s%n%nBitSetter trigger: machine Z %.3f%n", r.tool(), r.triggerZ()));
        if (r.repeatTriggerZ() != null) {
            b.append(String.format(Locale.ROOT, "Repeat trigger: machine Z %.3f (difference %.3f mm)%n",
                    r.repeatTriggerZ(), Math.abs(r.repeatTriggerZ() - r.triggerZ())));
        }
        b.append(String.format(Locale.ROOT, "Work offset Z (G54): %.3f%nReference (WCO Z − trigger): %.3f",
                r.wcoZ(), r.ref()));
        return b.toString();
    }

    private static String notReady() {
        try {
            BackendAPI backend = LookupService.lookup(BackendAPI.class);
            if (backend == null || !backend.isConnected()) {
                return "Connect to the machine first.";
            }
            if (backend.getControllerState() != ControllerState.IDLE) {
                return "The machine must be Idle (it is " + backend.getControllerState() + ").";
            }
        } catch (RuntimeException e) {
            return null; // let the runtime's own checks decide
        }
        return null;
    }

    private static void error(String text) {
        JOptionPane.showMessageDialog(null, text, "Measure BitSetter reference", JOptionPane.WARNING_MESSAGE);
    }
}

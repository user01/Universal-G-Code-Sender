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

import com.willwinder.ugs.nbp.bridge.api.OperatorPrompts;
import org.openide.windows.WindowManager;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.beans.PropertyChangeEvent;
import java.util.concurrent.CompletableFuture;

/**
 * Asks the operator in a non-modal dialog on top of UGS (so the rest of UGS, including stop and lock, stays
 * usable). Declined by default: closing the dialog, locking Claude access or an alarm counts as "no".
 */
public final class SwingOperatorPrompts implements OperatorPrompts {

    @Override
    public Prompt confirm(String title, String message, String approve) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        JDialog[] holder = new JDialog[1];
        if (GraphicsEnvironment.isHeadless()) {
            result.complete(false);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (result.isDone()) {
                    return;
                }
                Object[] options = {approve, "Decline"};
                JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION, null,
                        options, options[1]);
                Frame owner = WindowManager.getDefault().getMainWindow();
                JDialog dialog = pane.createDialog(owner, title);
                dialog.setModal(false);
                dialog.setAlwaysOnTop(true);
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, (PropertyChangeEvent e) -> {
                    Object v = e.getNewValue();
                    result.complete(approve.equals(v));
                    dialog.dispose();
                });
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        result.complete(false);
                    }
                });
                holder[0] = dialog;
                dialog.setVisible(true);
            });
        }
        return new Prompt() {
            @Override
            public CompletableFuture<Boolean> result() {
                return result;
            }

            @Override
            public void cancel() {
                result.complete(false);
                SwingUtilities.invokeLater(() -> {
                    if (holder[0] != null) {
                        holder[0].dispose();
                    }
                });
            }
        };
    }
}

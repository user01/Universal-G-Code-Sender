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
package com.willwinder.ugs.nbp.core.actions;

import java.awt.Component;

/**
 * Asked by {@link StartAction} before it starts sending a program (not when it resumes a paused one). Register an
 * implementation with {@code @ServiceProvider(service = StartGuard.class)}; any guard returning false cancels the
 * start. Called on the event dispatch thread, so a guard may show a dialog.
 */
public interface StartGuard {
    /**
     * @param parent the component the start came from, for dialogs (may be null)
     * @return true to start, false to cancel
     */
    boolean beforeStart(Component parent);
}

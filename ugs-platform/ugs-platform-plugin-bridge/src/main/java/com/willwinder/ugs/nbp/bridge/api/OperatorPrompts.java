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
package com.willwinder.ugs.nbp.bridge.api;

import java.util.concurrent.CompletableFuture;

/**
 * Asks the operator to approve something Claude requested ({@code $H}, {@code $X}, running a file). The UGS
 * implementation shows a non-modal dialog; tests script the answers.
 */
public interface OperatorPrompts {

    /** An open question. {@link #cancel()} closes it unanswered, which counts as declined. */
    interface Prompt {
        CompletableFuture<Boolean> result();

        void cancel();
    }

    /**
     * @param title   dialog title
     * @param message what Claude asks for, in plain words
     * @param approve label of the approve button
     */
    Prompt confirm(String title, String message, String approve);

    /** Declines everything (headless, or before the UI exists). */
    OperatorPrompts DECLINE = (title, message, approve) -> {
        CompletableFuture<Boolean> f = CompletableFuture.completedFuture(false);
        return new Prompt() {
            @Override
            public CompletableFuture<Boolean> result() {
                return f;
            }

            @Override
            public void cancel() {
            }
        };
    };
}

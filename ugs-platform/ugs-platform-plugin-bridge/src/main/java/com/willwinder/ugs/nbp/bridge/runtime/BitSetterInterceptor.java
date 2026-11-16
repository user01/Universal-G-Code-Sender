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
package com.willwinder.ugs.nbp.bridge.runtime;

import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.services.interceptor.CommandInterceptor;
import com.willwinder.universalgcodesender.services.interceptor.InterceptAbortedException;
import com.willwinder.universalgcodesender.services.interceptor.InterceptContext;
import com.willwinder.universalgcodesender.services.interceptor.InterceptException;
import com.willwinder.universalgcodesender.types.GcodeCommand;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The bridge's {@code M6} interceptor (SPEC §11.5, J3): park, operator prompt, BitSetter measurement, new G54 Z,
 * back to the pre-change XY at safe Z (D10); only the tool word when the tool is already in the collet with a known
 * length (D22). Registered with UGS's {@code CommandInterceptorService}; it matches
 * only while the bridge's "BitSetter tool changes" option is on (the runtime turns UGS's own tool-change handling
 * off for that time). The sequence itself is {@link ToolChangeRoutine}.
 *
 * <p>The operator prompt is UGS's interceptor prompt (the GenericDialog in the platform UI). Only the UGS dialog
 * answers it; the bridge exposes no way for Claude to continue a tool change.
 */
public final class BitSetterInterceptor implements CommandInterceptor {
    /** Same matching as UGS's ToolChangeInterceptor: an M6/M06 word, not M60/M61. */
    static final Pattern TOOL_CHANGE = Pattern.compile("(?i)(?<![A-Z])M0?6(?![0-9])");

    /** What the interceptor needs from the runtime. */
    public interface Host {
        boolean toolChangesEnabled();

        MachineProfile profile();

        /** The job being streamed (tool names, envelope), or null. */
        JobModel job();

        /** The BitSetter reference ({@code WCO_z − S}), or null. */
        Double reference();

        /**
         * D22: the tool in the collet if its length is known for certain (measured in this connection, with no
         * reset, alarm, homing or G54 Z change since), else null. An M6 for this tool skips the measurement.
         */
        String knownLengthTool();

        ToolChangeRoutine.Events events();

        /** Called after a successful change (also when D22 skipped it: {@code result.measured()} is false). */
        void toolChanged(ToolChangeRoutine.Result result, java.util.List<String> sent);

        /** Called when the routine ends without a change (abort or failure). */
        void toolChangeEnded(String outcome, String detail, java.util.List<String> sent);
    }

    private final Host host;
    private final Function<IController, MachineIo> ioFactory;

    public BitSetterInterceptor(Host host) {
        this(host, c -> new UgsMachineIo(c, null));
    }

    BitSetterInterceptor(Host host, Function<IController, MachineIo> ioFactory) {
        this.host = host;
        this.ioFactory = ioFactory;
    }

    @Override
    public boolean matches(GcodeCommand command) {
        if (!host.toolChangesEnabled()) {
            return false;
        }
        String s = command.getCommandString();
        String o = command.getOriginalCommandString();
        return (s != null && TOOL_CHANGE.matcher(strip(s)).find()) || (o != null && TOOL_CHANGE.matcher(strip(o)).find());
    }

    private static String strip(String line) {
        return line.replaceAll("\\([^)]*\\)", " ").replaceAll(";.*$", "");
    }

    @Override
    public boolean restoresPosition() {
        // D10: the routine returns to the pre-change XY at safe Z itself. UGS's restore would rapid to the work Z
        // captured before the change; with a G53 retract before M6 (as the posts do) that is the machine-safe Z
        // read as a *work* Z, i.e. below the stock top.
        return false;
    }

    @Override
    public void execute(InterceptContext context) throws InterceptException {
        GcodeCommand trigger = context.getTriggerCommand();
        MachineIo io = ioFactory.apply(context.getController());
        ToolChangeRoutine routine = new ToolChangeRoutine(host.profile(), io, context::awaitUserPrompt, host.events());
        String line = trigger.getOriginalCommandString() != null && !trigger.getOriginalCommandString().isBlank()
                ? trigger.getOriginalCommandString() : trigger.getCommandString();
        int fileLine = trigger.isGenerated() ? -1 : trigger.getCommandNumber();
        try {
            ToolChangeRoutine.Result r = routine.toolChange(line, fileLine, host.job(), host.reference(),
                    host.knownLengthTool());
            host.toolChanged(r, routine.sent());
        } catch (InterceptAbortedException e) {
            host.toolChangeEnded("aborted", e.getMessage(), routine.sent());
            throw e;
        } catch (ToolChangeRoutine.RoutineException e) {
            host.toolChangeEnded("failed", e.getMessage(), routine.sent());
            throw new InterceptException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            host.toolChangeEnded("interrupted", e.getMessage(), routine.sent());
            throw new InterceptException("Tool change was interrupted", e);
        } finally {
            if (io instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // detaching a listener
                }
            }
        }
    }
}

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
import com.willwinder.universalgcodesender.services.interceptor.InterceptAbortedException;
import com.willwinder.universalgcodesender.services.interceptor.InterceptContext;
import com.willwinder.universalgcodesender.services.interceptor.InterceptException;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorPrompt;
import com.willwinder.universalgcodesender.services.interceptor.UserResponse;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BitSetterInterceptorTest {
    private final JobModel job = JobModel.parse("two.nc", Fixtures.TWO_TOOLS, null);
    private final FakeMachine m = new FakeMachine();
    private final List<String> outcomes = new ArrayList<>();
    private boolean enabled = true;
    private Double ref = 10.0;

    private final BitSetterInterceptor.Host host = new BitSetterInterceptor.Host() {
        @Override
        public boolean toolChangesEnabled() {
            return enabled;
        }

        @Override
        public MachineProfile profile() {
            return Fixtures.profile();
        }

        @Override
        public JobModel job() {
            return job;
        }

        @Override
        public Double reference() {
            return ref;
        }

        @Override
        public ToolChangeRoutine.Events events() {
            return new Fixtures.Events();
        }

        @Override
        public void toolChanged(ToolChangeRoutine.Result result, List<String> sent) {
            outcomes.add("changed " + result.tool() + " " + result.wcoZ() + " " + sent.size());
        }

        @Override
        public void toolChangeEnded(String outcome, String detail, List<String> sent) {
            outcomes.add(outcome);
        }
    };

    private final BitSetterInterceptor interceptor = new BitSetterInterceptor(host, c -> m);

    private InterceptContext context(UserResponse answer) throws Exception {
        InterceptContext ctx = mock(InterceptContext.class);
        int line = 0;
        for (int i = 1; i <= job.lineCount(); i++) {
            if (job.line(i).equals("T21 M6")) {
                line = i;
            }
        }
        // UGS strips whitespace in the processed command; the original keeps it
        when(ctx.getTriggerCommand()).thenReturn(new GcodeCommand("T21M6", "T21 M6", "", line));
        if (answer == UserResponse.ABORT) {
            when(ctx.awaitUserPrompt(any(InterceptorPrompt.class))).thenThrow(new InterceptAbortedException("aborted"));
        } else {
            when(ctx.awaitUserPrompt(any(InterceptorPrompt.class))).thenReturn(answer);
        }
        return ctx;
    }

    @Test
    public void matchesM6OnlyWhileEnabled() {
        assertTrue(interceptor.matches(new GcodeCommand("T21M6", "T21 M6", "", 20)));
        assertTrue(interceptor.matches(new GcodeCommand("M06T2", "M06 T2", "", 7)));
        assertFalse(interceptor.matches(new GcodeCommand("M61", "M61", "", 7)));
        assertFalse(interceptor.matches(new GcodeCommand("G0X1", "G0 X1 (M6 later)", "M6 later", 7)));
        enabled = false;
        assertFalse(interceptor.matches(new GcodeCommand("T21M6", "T21 M6", "", 20)));
        assertFalse(interceptor.restoresPosition());
    }

    @Test
    public void runsTheRoutineThroughTheContext() throws Exception {
        m.triggerZ = -68.5;
        interceptor.execute(context(UserResponse.CONTINUE));
        assertEquals(List.of("changed T21 -58.5 20"), outcomes);
        assertEquals("T21", m.log.get(0));
    }

    @Test
    public void abortIsPassedToTheService() throws Exception {
        try {
            interceptor.execute(context(UserResponse.ABORT));
            fail("expected abort");
        } catch (InterceptAbortedException expected) {
            // the service cancels the stream
        }
        assertEquals(List.of("aborted"), outcomes);
        assertEquals("G4 P0", m.log.get(m.log.size() - 1));
    }

    @Test
    public void failureLeavesTheStreamPaused() throws Exception {
        ref = null;
        try {
            interceptor.execute(context(UserResponse.CONTINUE));
            fail("expected failure");
        } catch (InterceptAbortedException e) {
            fail("a failure must not cancel the stream");
        } catch (InterceptException expected) {
            assertTrue(expected.getMessage().contains("No BitSetter reference"));
        }
        assertEquals(List.of("failed"), outcomes);
    }
}

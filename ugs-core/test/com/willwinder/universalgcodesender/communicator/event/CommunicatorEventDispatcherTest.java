package com.willwinder.universalgcodesender.communicator.event;

import com.willwinder.universalgcodesender.communicator.ICommunicatorListener;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class CommunicatorEventDispatcherTest {

    private CommunicatorEventDispatcher eventDispatcher;

    @Before
    public void setUp() {
        eventDispatcher = new CommunicatorEventDispatcher();
    }

    @Test
    public void dispatchPauseEventShouldTriggerListener() {
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        eventDispatcher.addListener(listener);

        eventDispatcher.communicatorPausedOnError();

        verify(listener, times(1)).communicatorPausedOnError();
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void dispatchSentEventShouldTriggerListener() {
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        eventDispatcher.addListener(listener);

        eventDispatcher.commandSent(null);

        verify(listener, times(1)).commandSent(any());
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void dispatchSkippedEventShouldTriggerListener() {
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        eventDispatcher.addListener(listener);

        eventDispatcher.commandSkipped(null);

        verify(listener, times(1)).commandSkipped(any());
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void removeListenerShouldMakeEventsNoLongerDispatch() {
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        eventDispatcher.addListener(listener);
        eventDispatcher.removeListener(listener);

        eventDispatcher.commandSkipped(null);

        verifyNoMoreInteractions(listener);
    }

    @Test
    public void dispatchRealtimeByteShouldTriggerListener() {
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        eventDispatcher.addListener(listener);

        eventDispatcher.realtimeByteSent((byte) 0x85);

        verify(listener, times(1)).realtimeByteSent((byte) 0x85);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void listenersCanBeAddedAndRemovedWhileDispatching() {
        // ugs-bridge P4: with a HashSet this threw ConcurrentModificationException
        List<String> seen = new ArrayList<>();
        ICommunicatorListener late = mock(ICommunicatorListener.class);
        ICommunicatorListener[] self = new ICommunicatorListener[1];
        self[0] = new ICommunicatorListener() {
            @Override public void rawResponseListener(String response) {
                seen.add(response);
                eventDispatcher.addListener(late);
                eventDispatcher.removeListener(self[0]);
            }
            @Override public void commandSent(com.willwinder.universalgcodesender.types.GcodeCommand command) { }
            @Override public void commandSkipped(com.willwinder.universalgcodesender.types.GcodeCommand command) { }
            @Override public void communicatorPausedOnError() { }
            @Override public void onConnectionClosed() { }
        };
        eventDispatcher.addListener(self[0]);
        for (int i = 0; i < 5; i++) {
            eventDispatcher.addListener(mock(ICommunicatorListener.class));
        }

        eventDispatcher.rawResponseListener("ok");
        eventDispatcher.rawResponseListener("ok");

        assertEquals(List.of("ok"), seen);
        verify(late, times(1)).rawResponseListener("ok");
    }
}

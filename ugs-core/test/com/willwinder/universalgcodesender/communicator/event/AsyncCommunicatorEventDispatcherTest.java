package com.willwinder.universalgcodesender.communicator.event;

import com.willwinder.universalgcodesender.communicator.ICommunicatorListener;
import static com.willwinder.universalgcodesender.utils.ThreadHelper.waitUntil;
import org.junit.After;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AsyncCommunicatorEventDispatcherTest {
    private AsyncCommunicatorEventDispatcher eventDispatcher;

    @Before
    public void setUp() {
        eventDispatcher = new AsyncCommunicatorEventDispatcher();
    }

    @After
    public void tearDown() {
        eventDispatcher.reset();
    }

    @Test
    public void dispatchShouldStartWorkerThread() throws TimeoutException, InterruptedException {
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        eventDispatcher.addListener(listener);

        eventDispatcher.communicatorPausedOnError();
        Thread.sleep(100);

        waitUntil(() -> eventDispatcher.getEventCount() == 0, 2000, TimeUnit.MILLISECONDS);

        verify(listener, times(1)).communicatorPausedOnError();
    }

    @Test
    public void stopShouldInterruptListener() throws InterruptedException {
        // Simulate a long running listener
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        doAnswer(invocation -> {
            Thread.sleep(1000);
            return null;
        }).when(listener).commandSent(any());
        eventDispatcher.addListener(listener);
        eventDispatcher.commandSent(null);

        Thread.sleep(100);

        assertFalse(eventDispatcher.isStopped());
        eventDispatcher.reset();
        Thread.sleep(100);
        assertTrue(eventDispatcher.isStopped());
    }

    @Test
    public void dispatchShouldStopOnListenerException() throws InterruptedException {
        // Simulate a long running listener
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        doThrow(new RuntimeException()).when(listener).commandSent(any());
        eventDispatcher.addListener(listener);

        eventDispatcher.commandSent(null);

        Thread.sleep(100);
        assertTrue(eventDispatcher.isStopped());
    }

    @Test
    public void realtimeBytesAreDispatchedInOrderWithOtherEvents() throws Exception {
        ICommunicatorListener listener = mock(ICommunicatorListener.class);
        eventDispatcher.addListener(listener);

        eventDispatcher.commandSent(null);
        eventDispatcher.realtimeByteSent((byte) '!');
        eventDispatcher.rawResponseListener("ok");
        eventDispatcher.realtimeByteSent((byte) 0x18);

        waitUntil(() -> eventDispatcher.getEventCount() == 0, 2000, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(listener);
        order.verify(listener).commandSent(any());
        order.verify(listener).realtimeByteSent((byte) '!');
        order.verify(listener).rawResponseListener("ok");
        order.verify(listener).realtimeByteSent((byte) 0x18);
    }

    @Test
    public void eventsQueuedBeforeResetOnlyReachListenersThatAskForThem() throws Exception {
        // ugs-bridge P4: a reset used to throw queued events away (e.g. the second welcome line)
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ICommunicatorListener slow = mock(ICommunicatorListener.class);
        doAnswer(invocation -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // reset interrupts the event thread
            }
            return null;
        }).when(slow).rawResponseListener("Grbl 1.1f");
        ICommunicatorListener log = mock(ICommunicatorListener.class);
        org.mockito.Mockito.when(log.receivesDiscardedEvents()).thenReturn(true);
        eventDispatcher.addListener(slow);
        eventDispatcher.addListener(log);

        eventDispatcher.rawResponseListener("Grbl 1.1f");
        blocked.await(2, TimeUnit.SECONDS);
        eventDispatcher.rawResponseListener("[MSG:'$H'|'$X' to unlock]");
        eventDispatcher.reset();
        release.countDown();
        eventDispatcher.rawResponseListener("ok");

        waitUntil(() -> eventDispatcher.getEventCount() == 0, 2000, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(log);
        order.verify(log).rawResponseListener("Grbl 1.1f");
        order.verify(log).rawResponseListener("[MSG:'$H'|'$X' to unlock]");
        order.verify(log).rawResponseListener("ok");
        verify(slow, org.mockito.Mockito.never()).rawResponseListener("[MSG:'$H'|'$X' to unlock]");
        verify(slow).rawResponseListener("ok");
    }
}

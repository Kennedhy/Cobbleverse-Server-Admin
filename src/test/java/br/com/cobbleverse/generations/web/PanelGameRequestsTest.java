package br.com.cobbleverse.generations.web;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanelGameRequestsTest {
    @Test
    void expiredWorkKeepsQueueBoundedAndNeverExecutesLater() throws Exception {
        var requests = new PanelGameRequests(2, Duration.ZERO);
        var stalledGameThread = new ArrayDeque<Runnable>();
        var executions = new AtomicInteger();

        for (int attempt = 0; attempt < 5; attempt++) {
            var error = assertThrows(WebPanelService.Problem.class,
                    () -> requests.call(stalledGameThread::add, executions::incrementAndGet));
            assertEquals(503, error.status);
        }
        assertEquals(2, stalledGameThread.size());
        stalledGameThread.forEach(Runnable::run);
        assertEquals(0, executions.get());

        assertEquals(1, requests.call(Runnable::run, executions::incrementAndGet));
    }

    @Test
    void rejectedExecutionAndStartedFailureReturnTheirSlots() throws Exception {
        var requests = new PanelGameRequests(1, Duration.ZERO);
        var busy = assertThrows(WebPanelService.Problem.class, () -> requests.call(task -> {
            throw new RejectedExecutionException();
        }, () -> "not submitted"));
        assertEquals(503, busy.status);

        var conflict = new WebPanelService.Problem(409, "Revisão alterada.");
        assertSame(conflict, assertThrows(WebPanelService.Problem.class, () ->
                requests.call(Runnable::run, () -> { throw conflict; })));
        assertEquals("ready", requests.call(Runnable::run, () -> "ready"));
    }
}

package br.com.cobbleverse.generations.web;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Caps HTTP work submitted to the game thread, including expired work it has yet to dequeue. */
final class PanelGameRequests {
    private final Semaphore pending;
    private final long startTimeoutNanos;

    PanelGameRequests(int limit, Duration startTimeout) {
        pending = new Semaphore(limit);
        startTimeoutNanos = startTimeout.toNanos();
    }

    <T> T call(Executor gameThread, Callable<T> task) throws Exception {
        if (!pending.tryAcquire()) throw busy();
        var started = new CompletableFuture<Void>();
        var result = new CompletableFuture<T>();
        try {
            gameThread.execute(() -> {
                try {
                    if (started.complete(null)) result.complete(task.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    pending.release();
                }
            });
        } catch (RejectedExecutionException rejected) {
            pending.release();
            throw busy();
        }

        try {
            started.get(startTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            // Only report a timeout when this call won the race against the game thread.
            if (started.cancel(false)) throw busy();
        } catch (InterruptedException interrupted) {
            started.cancel(false);
            throw interrupted;
        }
        try {
            return result.get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException(failure.getCause());
        }
    }

    private static WebPanelService.Problem busy() {
        return new WebPanelService.Problem(503, "O servidor do jogo está ocupado. Aguarde alguns segundos e tente novamente.");
    }
}

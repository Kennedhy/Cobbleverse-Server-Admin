package br.com.cobbleverse.generations.integration;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** One bounded I/O worker per preview; cancellation interrupts its running work. */
final class ValidationIo implements AutoCloseable {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "cobbleverse-validation-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> task = executor.submit(() -> {
            if (result.isCancelled()) return;
            try { checkCancelled(); result.complete(work.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        result.whenComplete((value, failure) -> { if (result.isCancelled()) task.cancel(true); });
        return result;
    }

    static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Análise cancelada.");
    }

    @Override public void close() { executor.shutdownNow(); }
}

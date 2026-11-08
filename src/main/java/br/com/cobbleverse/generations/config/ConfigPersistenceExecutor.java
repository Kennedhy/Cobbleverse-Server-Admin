package br.com.cobbleverse.generations.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/** One small bounded file worker shared by panel and server commands. */
public final class ConfigPersistenceExecutor {
    private static ThreadPoolExecutor current;
    private static final AtomicLong rejected = new AtomicLong();

    private ConfigPersistenceExecutor() {}

    public static synchronized Executor executor() {
        if (current == null || current.isShutdown()) {
            current = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "cobbleverse-config-persistence");
                thread.setDaemon(true);
                return thread;
            }, (task, executor) -> {
                rejected.incrementAndGet();
                throw new RejectedExecutionException("A fila de persistência está cheia.");
            });
            current.allowCoreThreadTimeOut(true);
        }
        return current;
    }

    public static synchronized void stop() {
        if (current == null) return;
        current.shutdown();
        current = null;
    }

    public static synchronized Map<String, Object> metrics() {
        return Map.of("active", current == null ? 0 : current.getActiveCount(),
                "queued", current == null ? 0 : current.getQueue().size(),
                "capacity", 2, "rejected", rejected.get());
    }
}

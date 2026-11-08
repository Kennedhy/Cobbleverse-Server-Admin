package br.com.cobbleverse.generations.web;

import net.minecraft.server.MinecraftServer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** Bounded, on-demand server samples. No periodic tick listener or disk writes are used. */
final class ServerMetrics {
    private static final long SAMPLE_INTERVAL_MS = 15_000;
    private static final long RETENTION_MS = 30 * 60_000;
    private static final int MAX_SAMPLES = 120;
    private static final ArrayDeque<Sample> SAMPLES = new ArrayDeque<>(MAX_SAMPLES);
    private static MinecraftServer owner;
    private static long lastSampleAt;
    private static long previousGcCollections = -1;
    private static long previousGcTimeMs = -1;

    record Sample(long time, double tickMs, double estimatedTps, long heapUsedMb, long heapMaxMb,
                  Double processCpuPercent, long gcCollections, long gcTimeMs,
                  long gcCollectionDelta, long gcTimeDeltaMs) {}

    private ServerMetrics() {}

    static synchronized Map<String, Object> snapshot(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (owner != server) {
            SAMPLES.clear();
            owner = server;
            lastSampleAt = 0;
            previousGcCollections = -1;
            previousGcTimeMs = -1;
        }
        if (SAMPLES.isEmpty() || now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
            Runtime runtime = Runtime.getRuntime();
            double tickMs = Math.max(0, server.getAverageTickTime());
            long gcCollections = 0, gcTimeMs = 0;
            for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (collector.getCollectionCount() > 0) gcCollections += collector.getCollectionCount();
                if (collector.getCollectionTime() > 0) gcTimeMs += collector.getCollectionTime();
            }
            long gcCollectionDelta = previousGcCollections < 0 ? 0 : Math.max(0, gcCollections - previousGcCollections);
            long gcTimeDeltaMs = previousGcTimeMs < 0 ? 0 : Math.max(0, gcTimeMs - previousGcTimeMs);
            SAMPLES.addLast(new Sample(now, tickMs, estimateTps(tickMs),
                    (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                    runtime.maxMemory() / (1024 * 1024), processCpuPercent(), gcCollections, gcTimeMs,
                    gcCollectionDelta, gcTimeDeltaMs));
            previousGcCollections = gcCollections;
            previousGcTimeMs = gcTimeMs;
            lastSampleAt = now;
        }
        while (!SAMPLES.isEmpty() && now - SAMPLES.peekFirst().time() > RETENTION_MS) SAMPLES.removeFirst();
        while (SAMPLES.size() > MAX_SAMPLES) SAMPLES.removeFirst();
        return Map.of("intervalMs", SAMPLE_INTERVAL_MS, "retentionMs", RETENTION_MS, "samples", List.copyOf(SAMPLES));
    }

    static double estimateTps(double tickMs) {
        return !Double.isFinite(tickMs) || tickMs <= 0 ? 20.0 : Math.min(20.0, 1000.0 / tickMs);
    }

    private static Double processCpuPercent() {
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
                double load = operatingSystem.getProcessCpuLoad();
                return Double.isFinite(load) && load >= 0 ? load * 100.0 : null;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    static synchronized void clear() {
        SAMPLES.clear();
        owner = null;
        lastSampleAt = 0;
        previousGcCollections = -1;
        previousGcTimeMs = -1;
    }
}

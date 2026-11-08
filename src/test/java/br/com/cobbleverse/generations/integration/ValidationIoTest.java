package br.com.cobbleverse.generations.integration;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ValidationIoTest {
    @Test void cancellingWorkInterruptsTheOwnedIoWorker() throws Exception {
        try (var io = new ValidationIo()) {
            var entered = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var task = io.submit(() -> {
                entered.countDown();
                try {
                    while (true) Thread.sleep(25);
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    return null;
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            task.cancel(false);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }
}

package com.tpverp.bridge.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.bridge.spi.OperationResult;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIdempotencyStoreTest {
    @TempDir Path temporary;

    @Test
    void survivesAProcessRestartWithoutExecutingTheOperationAgain() throws Exception {
        var calls = new AtomicInteger();
        var mapper = new ObjectMapper();
        var firstStore = new FileIdempotencyStore(temporary, mapper);
        var result = firstStore.execute("terminal:CHARGE", "key", "fingerprint", () -> {
            calls.incrementAndGet();
            return new OperationResult(true, "APPROVED", "R1", null, "OK", null);
        });
        var restartedStore = new FileIdempotencyStore(temporary, mapper);
        var repeated = restartedStore.execute("terminal:CHARGE", "key", "fingerprint", () -> {
            calls.incrementAndGet();
            return OperationResult.failure("ERROR", "must not run");
        });

        assertThat(result).isEqualTo(repeated);
        assertThat(calls).hasValue(1);
    }

    @Test
    void leavesAnUnknownAdapterFailureBlockedForManualQuery() throws Exception {
        var calls = new AtomicInteger();
        var store = new FileIdempotencyStore(temporary, new ObjectMapper());
        var first = store.execute("terminal:CHARGE", "key", "fingerprint", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("connection lost");
        });
        var repeated = store.execute("terminal:CHARGE", "key", "fingerprint", () -> {
            calls.incrementAndGet();
            return new OperationResult(true, "APPROVED", "R1", null, "OK", null);
        });

        assertThat(first.code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(repeated.code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void serializesConcurrentRequestsWithTheSameKey() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new FileIdempotencyStore(temporary, new ObjectMapper());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = CompletableFuture.supplyAsync(() -> store.execute("terminal:CHARGE", "key", "fingerprint", () -> {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new OperationResult(true, "APPROVED", "R1", null, "OK", null);
            }), executor);
            entered.await();
            var second = CompletableFuture.supplyAsync(() -> store.execute("terminal:CHARGE", "key", "fingerprint", () -> {
                calls.incrementAndGet();
                return OperationResult.failure("ERROR", "must not run");
            }), executor);
            release.countDown();

            assertThat(first.join()).isEqualTo(second.join());
            assertThat(calls).hasValue(1);
        }
    }
}

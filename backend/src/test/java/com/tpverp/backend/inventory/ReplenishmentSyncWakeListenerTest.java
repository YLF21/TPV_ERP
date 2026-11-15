package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.servertransfer.ServerTransferFence;
import com.tpverp.backend.sync.SyncOutboxWorker;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReplenishmentSyncWakeListenerTest {
    @Test
    void wakesOnlyAfterCommitAndNeverOnRollback() throws Exception {
        var worker = mock(SyncOutboxWorker.class);
        try (var context = context(worker, true, true)) {
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID rolledBack = UUID.randomUUID();
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new ReplenishmentSyncRequested(rolledBack));
                status.setRollbackOnly();
            });
            context.getBean("replenishmentSyncExecutor", ThreadPoolTaskExecutor.class)
                    .submit(() -> { }).get(1, TimeUnit.SECONDS);
            verify(worker, never()).runEvent(rolledBack);

            UUID committed = UUID.randomUUID();
            var called = new CountDownLatch(1);
            doAnswer(invocation -> { called.countDown(); return 1; }).when(worker).runEvent(committed);
            transaction.executeWithoutResult(status -> context.publishEvent(new ReplenishmentSyncRequested(committed)));
            assertThat(called.await(1, TimeUnit.SECONDS)).isTrue();
            verify(worker).runEvent(committed);
        }
        var method = ReplenishmentSyncWakeListener.class.getMethod("onCommitted", ReplenishmentSyncRequested.class);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo("replenishmentSyncExecutor");
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void commitDoesNotWaitForBlockedNetworkWorker() throws Exception {
        var worker = mock(SyncOutboxWorker.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return 1;
        }).when(worker).runEvent(any());
        try (var context = context(worker, true, true)) {
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var thread = Executors.newSingleThreadExecutor();
            try {
                var future = thread.submit(() -> transaction.executeWithoutResult(status ->
                        context.publishEvent(new ReplenishmentSyncRequested(UUID.randomUUID()))));
                assertThat(future.get(500, TimeUnit.MILLISECONDS)).isNull();
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
                thread.shutdownNow();
            }
        }
    }

    @Test
    void disabledWorkerOrFrozenFenceLeavesEventForScheduler() {
        var worker = mock(SyncOutboxWorker.class);
        var fence = mock(ServerTransferFence.class);
        var event = new ReplenishmentSyncRequested(UUID.randomUUID());
        new ReplenishmentSyncWakeListener(worker, new MockEnvironment()
                .withProperty("tpv.sync.worker-enabled", "false"), fence).onCommitted(event);
        verify(fence, never()).enter();
        new ReplenishmentSyncWakeListener(worker, new MockEnvironment()
                .withProperty("tpv.sync.worker-enabled", "true"), fence).onCommitted(event);
        verify(fence).enter();
        verify(worker, never()).runEvent(any());
    }

    private AnnotationConfigApplicationContext context(SyncOutboxWorker worker, boolean enabled, boolean fenceOpen) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(TestConfiguration.class);
        context.registerBean(SyncOutboxWorker.class, () -> worker);
        context.registerBean(ServerTransferFence.class, () -> {
            var fence = mock(ServerTransferFence.class);
            when(fence.enter()).thenReturn(fenceOpen);
            return fence;
        });
        context.registerBean(Environment.class, () -> new MockEnvironment()
                .withProperty("tpv.sync.worker-enabled", Boolean.toString(enabled)));
        context.registerBean(ReplenishmentSyncConfiguration.class);
        context.registerBean(ReplenishmentSyncWakeListener.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean PlatformTransactionManager transactionManager() { return new TestTransactionManager(); }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,
                org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
    }
}

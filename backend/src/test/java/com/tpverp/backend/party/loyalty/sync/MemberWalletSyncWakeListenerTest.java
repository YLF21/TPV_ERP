package com.tpverp.backend.party.loyalty.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;

import com.tpverp.backend.sync.SyncOutboxWorker;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class MemberWalletSyncWakeListenerTest {

    @Test
    void despiertaSoloElEventoDirigido() throws Exception {
        var worker = mock(SyncOutboxWorker.class);
        var environment = new MockEnvironment().withProperty("tpv.sync.worker-enabled", "true");
        var listener = new MemberWalletSyncWakeListener(worker, environment);
        UUID eventId = UUID.randomUUID();

        listener.onCommitted(new MemberWalletLotSyncRequested(eventId));

        verify(worker).runEvent(eventId);
        var method = MemberWalletSyncWakeListener.class
                .getMethod("onCommitted", MemberWalletLotSyncRequested.class);
        assertThat(method.getAnnotation(Async.class).value())
                .isEqualTo("memberWalletSyncWakeExecutor");
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void creditoDevolucionSeIntentaDeFormaSincronaTrasCommit() throws Exception {
        var worker = mock(SyncOutboxWorker.class);
        var environment = new MockEnvironment().withProperty("tpv.sync.worker-enabled", "true");
        var listener = new MemberWalletSyncWakeListener(worker, environment);
        UUID eventId = UUID.randomUUID();

        listener.onReturnCreditCommitted(new MemberReturnCreditSyncRequested(eventId));

        verify(worker).runEvent(eventId);
        var method = MemberWalletSyncWakeListener.class
                .getMethod("onReturnCreditCommitted", MemberReturnCreditSyncRequested.class);
        assertThat(method.getAnnotation(Async.class)).isNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void falloDeCreditoDevolucionNoPropagaLaExcepcionAlCierre() {
        var worker = mock(SyncOutboxWorker.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("central down"))
                .when(worker).runEvent(org.mockito.ArgumentMatchers.any());
        var environment = new MockEnvironment().withProperty("tpv.sync.worker-enabled", "true");
        var listener = new MemberWalletSyncWakeListener(worker, environment);

        listener.onReturnCreditCommitted(new MemberReturnCreditSyncRequested(UUID.randomUUID()));

        verify(worker).runEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void creditoDevolucionNoSeIntentaSiElWorkerEstaDeshabilitado() {
        var worker = mock(SyncOutboxWorker.class);
        var environment = new MockEnvironment().withProperty("tpv.sync.worker-enabled", "false");
        var listener = new MemberWalletSyncWakeListener(worker, environment);

        listener.onReturnCreditCommitted(new MemberReturnCreditSyncRequested(UUID.randomUUID()));

        verify(worker, never()).runEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rollbackNoDespiertaYCommitSi() throws Exception {
        var worker = org.mockito.Mockito.mock(SyncOutboxWorker.class);
        try (var context = context(worker, true)) {
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID committed = UUID.randomUUID();
            transaction.executeWithoutResult(status -> context.publishEvent(
                    new MemberWalletLotSyncRequested(committed)));
            verify(worker, timeout(1000)).runEvent(committed);

            UUID rolledBack = UUID.randomUUID();
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new MemberWalletLotSyncRequested(rolledBack));
                status.setRollbackOnly();
            });
            context.getBean("memberWalletSyncWakeExecutor", ThreadPoolTaskExecutor.class)
                    .submit(() -> { }).get(1, TimeUnit.SECONDS);
            verify(worker, never()).runEvent(rolledBack);
        }
    }

    @Test
    void commitNoEsperaAunqueElWorkerEsteRetenido() throws Exception {
        var worker = org.mockito.Mockito.mock(SyncOutboxWorker.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return 0;
        }).when(worker).runEvent(org.mockito.ArgumentMatchers.any());
        try (var context = context(worker, true)) {
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID eventId = UUID.randomUUID();
            var transactionExecutor = Executors.newSingleThreadExecutor();
            try {
                var future = transactionExecutor.submit(() ->
                        transaction.executeWithoutResult(status -> context.publishEvent(
                                new MemberWalletLotSyncRequested(eventId))));
                assertThat(future.get(500, TimeUnit.MILLISECONDS)).isNull();
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                release.countDown();
            } finally {
                transactionExecutor.shutdownNow();
            }
        }
    }

    @Test
    void wakeEnRafagaMantieneConcurrenciaUno() throws Exception {
        var worker = org.mockito.Mockito.mock(SyncOutboxWorker.class);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new CountDownLatch(20);
        doAnswer(invocation -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            Thread.sleep(5);
            active.decrementAndGet();
            calls.countDown();
            return 0;
        }).when(worker).runEvent(org.mockito.ArgumentMatchers.any());
        try (var context = context(worker, true)) {
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            for (int index = 0; index < 20; index++) {
                transaction.executeWithoutResult(status -> context.publishEvent(
                        new MemberWalletLotSyncRequested(UUID.randomUUID())));
            }
            assertThat(calls.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(1);
        }
    }

    @Test
    void workerDesactivadoNoConsumeElWake() {
        var worker = org.mockito.Mockito.mock(SyncOutboxWorker.class);
        var environment = new MockEnvironment().withProperty("tpv.sync.worker-enabled", "false");
        var listener = new MemberWalletSyncWakeListener(worker, environment);

        listener.onCommitted(new MemberWalletLotSyncRequested(UUID.randomUUID()));

        verify(worker, never()).runEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saturacionRechazaSinEjecutarEnCallerYDespuesDrena() throws Exception {
        var configuration = new MemberWalletSyncWakeConfiguration();
        ThreadPoolTaskExecutor executor = configuration.memberWalletSyncWakeExecutor();
        var release = new CountDownLatch(1);
        var drained = new CountDownLatch(256);
        var callerRan = new AtomicBoolean();
        try {
            executor.execute(() -> await(release));
            for (int index = 0; index < 256; index++) {
                executor.execute(() -> {
                    drained.countDown();
                });
            }
            executor.execute(() -> callerRan.set(true));
            assertThat(callerRan).isFalse();
            release.countDown();
            assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private AnnotationConfigApplicationContext context(SyncOutboxWorker worker, boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(TestConfiguration.class);
        context.registerBean(SyncOutboxWorker.class, () -> worker);
        context.registerBean(Environment.class, () -> new MockEnvironment()
                .withProperty("tpv.sync.worker-enabled", Boolean.toString(enabled)));
        context.registerBean(MemberWalletSyncWakeConfiguration.class);
        context.registerBean(MemberWalletSyncWakeListener.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction,
                org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }
    }
}

package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboxService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the real commit callback, JPA refresh, revision lock and durable outbox in an isolated test schema. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, DocumentSyncPublisher.class, DocumentSyncPayloadFactory.class,
        DocumentSyncRevisionRepository.class, SyncOutboxService.class, DocumentAttributionResolver.class,
        DocumentSyncPublisherPostgreSqlTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class DocumentSyncPublisherPostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "document_sync_publisher_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired DocumentSyncPublisher publisher;
    @Autowired CommercialDocumentRepository documents;
    @Autowired DocumentRelationRepository relations;
    @Autowired PaymentMethodRepository paymentMethods;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean SyncOutboxService outbox;
    @MockitoSpyBean DocumentSyncRevisionRepository revisions;
    private Fixture fixture;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void prepareCommittedSyntheticMasters() {
        fixture = Objects.requireNonNull(transaction().execute(status -> createFixture()));
    }

    @AfterEach
    void restoreOutboxSpy() {
        reset(outbox);
        DocumentSyncRevisionRepository revisionSpy = AopTestUtils.getUltimateTargetObject(revisions);
        reset(revisionSpy);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!SCHEMA.matches("document_sync_publisher_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected test schema");
        }
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void coalescesRepeatedSchedulesAndIncludesPaymentAndRelationAddedAfterScheduling() {
        assertThat(jdbc.queryForObject("""
                select max(version::integer) from flyway_schema_history
                 where success = true and version ~ '^[0-9]+$'
                """, Integer.class)).isEqualTo(244);
        UUID documentId = Objects.requireNonNull(transaction().execute(status -> {
            var document = documents.save(newDocument(CommercialDocumentType.FACTURA_VENTA));
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.CONFIRMAR);

            addPayment(document, "10.00");
            relations.save(new DocumentRelation(document,
                    documents.findById(fixture.originId()).orElseThrow(), DocumentRelationType.FACTURA_DE));
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
            return document.getId();
        }));

        assertThat(revision(documentId)).isEqualTo(1L);
        assertThat(events(documentId)).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo("CONFIRMAR");
            assertThat(event.revision()).isEqualTo(1L);
            assertThat(event.payload().get("estado").textValue()).isEqualTo("PAGADO");
            assertThat(event.payload().get("pagos").size()).isEqualTo(1);
            assertThat(event.payload().at("/pagos/0/importe").textValue()).isEqualTo("10.00");
            assertThat(event.payload().get("relaciones").size()).isEqualTo(1);
            assertThat(event.payload().at("/relaciones/0/tipo").textValue()).isEqualTo("FACTURA_DE");
            assertThat(event.payload().at("/relaciones/0/origenId").textValue())
                    .isEqualTo(fixture.originId().toString());
        });
        assertThat(jdbc.queryForObject("select estado from documento where id=?", String.class, documentId))
                .isEqualTo("PAGADO");
        assertThat(jdbc.queryForObject("select count(*) from documento_pago where documento_id=?",
                Integer.class, documentId)).isEqualTo(1);
    }

    @Test
    void consecutiveBusinessTransactionsProduceIncreasingRevisionsWithoutChangingEarlierSnapshots() {
        UUID documentId = createPublishedInvoice();
        transaction().executeWithoutResult(status -> {
            var document = documents.findById(documentId).orElseThrow();
            addPayment(document, "4.00");
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
        });
        transaction().executeWithoutResult(status -> {
            var document = documents.findById(documentId).orElseThrow();
            addPayment(document, "6.00");
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
        });

        List<Event> events = events(documentId);
        assertThat(revision(documentId)).isEqualTo(3L);
        assertThat(events).extracting(Event::revision).containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(event -> event.payload().get("estado").textValue())
                .containsExactly("PENDIENTE", "PARCIAL", "PAGADO");
        assertThat(events).extracting(event -> event.payload().get("pagos").size()).containsExactly(0, 1, 2);
        assertThat(events).extracting(Event::operation).containsExactly("CONFIRMAR", "ACTUALIZAR", "ACTUALIZAR");
    }

    @Test
    void initialPublicationCoalescesAndRepeatedRecoveryNeverChangesAnExistingRevisionOrSnapshot() {
        UUID documentId = createUnpublishedInvoice();
        transaction().executeWithoutResult(status -> {
            var document = documents.findById(documentId).orElseThrow();
            publisher.scheduleIfUnpublished(fixture.companyId(), document, null);
            publisher.scheduleIfUnpublished(fixture.companyId(), document, null);
        });
        List<Event> initial = events(documentId);
        assertThat(initial).singleElement().satisfies(event -> {
            assertThat(event.revision()).isEqualTo(1L);
            assertThat(event.operation()).isEqualTo("ACTUALIZAR");
            assertThat(event.payload().get("estado").textValue()).isEqualTo("PENDIENTE");
        });

        transaction().executeWithoutResult(status -> publisher.scheduleIfUnpublished(fixture.companyId(),
                documents.findById(documentId).orElseThrow(), null));
        assertThat(revision(documentId)).isEqualTo(1L);
        assertThat(events(documentId)).isEqualTo(initial);

        transaction().executeWithoutResult(status -> {
            var document = documents.findById(documentId).orElseThrow();
            addPayment(document, "10.00");
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
        });
        List<Event> afterPayment = events(documentId);
        transaction().executeWithoutResult(status -> publisher.scheduleIfUnpublished(fixture.companyId(),
                documents.findById(documentId).orElseThrow(), null));
        assertThat(revision(documentId)).isEqualTo(2L);
        assertThat(events(documentId)).isEqualTo(afterPayment);
        assertThat(afterPayment.getFirst()).isEqualTo(initial.getFirst());
    }

    @Test
    void failureAfterInitialOutboxInsertRollsBackClaimAndCanBeRetriedWithoutChangingBusinessData() {
        UUID documentId = createUnpublishedInvoice();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entities.flush();
            assertThat(revision(documentId)).isEqualTo(1L);
            assertThat(events(documentId)).hasSize(1);
            throw new IllegalStateException("forced-initial-publication-failure");
        }).when(outbox).enqueue(any());

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> publisher.scheduleIfUnpublished(
                fixture.companyId(), documents.findById(documentId).orElseThrow(), null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("forced-initial-publication-failure");

        assertThat(revision(documentId)).isZero();
        assertThat(events(documentId)).isEmpty();
        assertUnpaidBusinessDataUnchanged(documentId);
        reset(outbox);
        transaction().executeWithoutResult(status -> publisher.scheduleIfUnpublished(fixture.companyId(),
                documents.findById(documentId).orElseThrow(), null));
        assertThat(revision(documentId)).isEqualTo(1L);
        assertThat(events(documentId)).hasSize(1);
        assertUnpaidBusinessDataUnchanged(documentId);
    }

    @Test
    void simultaneousInitialRecoveriesCreateExactlyOneRevisionAndOneEvent() throws Exception {
        assertConcurrentInitialPublications(false, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initialRecoveryAndFirstOperationalPublicationRespectTheWinningClaim(boolean operationalFirst) throws Exception {
        assertConcurrentInitialPublications(operationalFirst, !operationalFirst);
    }

    @Test
    void outboxFailureAfterItsRealInsertRollsBackDocumentLinesRevisionAndOutboxTogether() {
        var candidate = newDocument(CommercialDocumentType.FACTURA_VENTA);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            // Force the actual outbox INSERT before failing; absence afterward must be a database rollback.
            entities.flush();
            throw new IllegalStateException("forced-sync-outbox-failure");
        }).when(outbox).enqueue(any());

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            var document = documents.save(candidate);
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.CONFIRMAR);
        })).isInstanceOf(IllegalStateException.class).hasMessage("forced-sync-outbox-failure");

        assertAbsent(candidate.getId());
    }

    @Test
    void secondOutboxFailureRollsBackBothDocumentsAndTheFirstAlreadyInsertedPublication() {
        var firstCandidate = newDocument(CommercialDocumentType.FACTURA_VENTA);
        var secondCandidate = newDocument(CommercialDocumentType.FACTURA_VENTA);
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 2) throw new IllegalStateException("forced-second-outbox-failure");
            Object result = invocation.callRealMethod();
            entities.flush();
            assertThat(jdbc.queryForObject("""
                    select count(*) from sync_outbox where empresa_id=? and tipo_entidad='DOCUMENTO'
                    """, Integer.class, fixture.companyId())).isEqualTo(1);
            return result;
        }).when(outbox).enqueue(any());

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            var first = documents.save(firstCandidate);
            var second = documents.save(secondCandidate);
            publisher.schedule(fixture.companyId(), first, null, SyncOperation.CONFIRMAR);
            publisher.schedule(fixture.companyId(), second, null, SyncOperation.CONFIRMAR);
        })).isInstanceOf(IllegalStateException.class).hasMessage("forced-second-outbox-failure");

        assertThat(attempts.get()).isEqualTo(2);
        assertAbsent(firstCandidate.getId());
        assertAbsent(secondCandidate.getId());
    }

    @Test
    void businessRollbackBeforeCommitPublishesNothingAndDoesNotAllocateARevision() {
        var candidate = newDocument(CommercialDocumentType.FACTURA_VENTA);
        transaction().executeWithoutResult(status -> {
            var document = documents.saveAndFlush(candidate);
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.CONFIRMAR);
            status.setRollbackOnly();
        });

        assertAbsent(candidate.getId());
    }

    @Test
    void requiresNewUsesItsOwnBufferAndItsCommittedPublicationSurvivesOuterRollback() {
        var outerCandidate = newDocument(CommercialDocumentType.FACTURA_VENTA);
        var innerCandidate = newDocument(CommercialDocumentType.FACTURA_VENTA);
        transaction().executeWithoutResult(status -> {
            var outer = documents.save(outerCandidate);
            publisher.schedule(fixture.companyId(), outer, null, SyncOperation.CONFIRMAR);
            var requiresNew = transaction();
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNew.executeWithoutResult(innerStatus -> {
                var inner = documents.save(innerCandidate);
                publisher.schedule(fixture.companyId(), inner, null, SyncOperation.CONFIRMAR);
            });
            assertThat(events(innerCandidate.getId())).hasSize(1);
            assertThat(events(outerCandidate.getId())).isEmpty();
            // Scheduling again proves the resumed resource is the outer buffer, not the completed inner one.
            publisher.schedule(fixture.companyId(), outer, null, SyncOperation.ACTUALIZAR);
            status.setRollbackOnly();
        });

        assertAbsent(outerCandidate.getId());
        assertThat(revision(innerCandidate.getId())).isEqualTo(1L);
        assertThat(events(innerCandidate.getId())).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo("CONFIRMAR");
            assertThat(event.payload().get("numero").textValue()).isEqualTo(innerCandidate.getNumero());
        });
        // Completion must release the thread-local buffer for the next independent transaction too.
        UUID laterId = createPublishedInvoice();
        assertThat(revision(laterId)).isEqualTo(1L);
        assertThat(events(laterId)).hasSize(1);
    }

    @Test
    void relationOnlyTransactionRefreshesHeaderAndInitializedPaymentsAfterAConcurrentPaymentCommit() throws Exception {
        UUID documentId = createPublishedInvoice();
        var staleDocumentLoaded = new CountDownLatch(1);
        var paymentCommitted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        Future<?> relationTransaction = null;
        try {
            relationTransaction = executor.submit(() -> transaction().executeWithoutResult(status -> {
                var staleDocument = documents.findById(documentId).orElseThrow();
                assertThat(staleDocument.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
                assertThat(staleDocument.getPagos()).isEmpty();
                publisher.schedule(fixture.companyId(), staleDocument, null, SyncOperation.ACTUALIZAR);
                staleDocumentLoaded.countDown();
                await(paymentCommitted);
                // No header setter is called in this transaction: the managed object still has its old state.
                assertThat(staleDocument.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
                assertThat(staleDocument.getPagos()).isEmpty();
                relations.save(new DocumentRelation(staleDocument,
                        documents.findById(fixture.originId()).orElseThrow(), DocumentRelationType.FACTURA_DE));
            }));
            await(staleDocumentLoaded);
            transaction().executeWithoutResult(status -> {
                var document = documents.findById(documentId).orElseThrow();
                addPayment(document, "10.00");
                publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
            });
            paymentCommitted.countDown();
            relationTransaction.get(15, TimeUnit.SECONDS);
        } finally {
            paymentCommitted.countDown();
            if (relationTransaction != null && !relationTransaction.isDone()) relationTransaction.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        List<Event> events = events(documentId);
        assertThat(events).extracting(Event::revision).containsExactly(1L, 2L, 3L);
        assertThat(events.get(1).payload().get("relaciones").size()).isZero();
        Event latest = events.getLast();
        assertThat(latest.payload().get("estado").textValue()).isEqualTo("PAGADO");
        assertThat(latest.payload().get("pagos").size()).isEqualTo(1);
        assertThat(latest.payload().at("/pagos/0/importe").textValue()).isEqualTo("10.00");
        assertThat(latest.payload().get("relaciones").size()).isEqualTo(1);
        assertThat(latest.payload().at("/relaciones/0/origenId").textValue())
                .isEqualTo(fixture.originId().toString());
        assertThat(revision(documentId)).isEqualTo(3L);
    }

    @Test
    void simultaneousFirstPublicationsAllocateOneCounterAndExactlyTwoSuccessiveRevisions() throws Exception {
        UUID documentId = Objects.requireNonNull(transaction().execute(status ->
                documents.save(newDocument(CommercialDocumentType.FACTURA_VENTA)).getId()));
        assertThat(revision(documentId)).isZero();
        assertThat(events(documentId)).isEmpty();
        var readyToCommit = new CountDownLatch(2);
        var startCommitting = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var futures = new java.util.ArrayList<Future<?>>();
        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> transaction().executeWithoutResult(status -> {
                    var document = documents.findById(documentId).orElseThrow();
                    publisher.schedule(fixture.companyId(), document, null, SyncOperation.ACTUALIZAR);
                    readyToCommit.countDown();
                    await(startCommitting);
                })));
            }
            await(readyToCommit);
            startCommitting.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            startCommitting.countDown();
            for (var future : futures) if (!future.isDone()) future.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(events(documentId)).extracting(Event::revision).containsExactly(1L, 2L);
        assertThat(events(documentId)).extracting(Event::operation).containsExactly("ACTUALIZAR", "ACTUALIZAR");
        assertThat(revision(documentId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from documento_sync_revision where documento_id=?",
                Integer.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from documento where id=?", Integer.class, documentId))
                .isEqualTo(1);
    }

    private void assertConcurrentInitialPublications(boolean firstOperational, boolean secondOperational) throws Exception {
        UUID documentId = createUnpublishedInvoice();
        var firstEnqueued = new CountDownLatch(1);
        var secondAllocationAttempted = new CountDownLatch(1);
        var allocationAttempts = new AtomicInteger();
        var publicationAttempts = new AtomicInteger();
        // Stub the target, not its MANDATORY transaction interceptor, outside the worker transactions.
        DocumentSyncRevisionRepository revisionSpy = AopTestUtils.getUltimateTargetObject(revisions);
        doAnswer(invocation -> {
            if (allocationAttempts.incrementAndGet() == 2) secondAllocationAttempted.countDown();
            return invocation.callRealMethod();
        }).when(revisionSpy).nextRevision(documentId);
        doAnswer(invocation -> {
            if (allocationAttempts.incrementAndGet() == 2) secondAllocationAttempted.countDown();
            return invocation.callRealMethod();
        }).when(revisionSpy).tryClaimInitialRevision(documentId);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            entities.flush();
            if (publicationAttempts.incrementAndGet() == 1) {
                // Hold the first transaction's real revision lock while the second reaches its allocation.
                firstEnqueued.countDown();
                await(secondAllocationAttempted);
            }
            return result;
        }).when(outbox).enqueue(any());

        var executor = Executors.newFixedThreadPool(2);
        var futures = new java.util.ArrayList<Future<?>>();
        try {
            futures.add(executor.submit(() -> scheduleInitialPublication(documentId, firstOperational)));
            await(firstEnqueued);
            futures.add(executor.submit(() -> scheduleInitialPublication(documentId, secondOperational)));
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            secondAllocationAttempted.countDown();
            for (var future : futures) if (!future.isDone()) future.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        long expectedRevision = secondOperational ? 2L : 1L;
        assertThat(allocationAttempts.get()).isEqualTo(2);
        assertThat(revision(documentId)).isEqualTo(expectedRevision);
        if (secondOperational) {
            assertThat(events(documentId)).extracting(Event::revision).containsExactly(1L, 2L);
            assertThat(events(documentId)).extracting(Event::operation).containsExactly("ACTUALIZAR", "CONFIRMAR");
        } else {
            assertThat(events(documentId)).singleElement().satisfies(event -> {
                assertThat(event.revision()).isEqualTo(1L);
                assertThat(event.operation()).isEqualTo(firstOperational ? "CONFIRMAR" : "ACTUALIZAR");
            });
        }
        assertThat(jdbc.queryForObject("select count(*) from documento_sync_revision where documento_id=?",
                Integer.class, documentId)).isEqualTo(1);
        assertUnpaidBusinessDataUnchanged(documentId);
    }

    private void scheduleInitialPublication(UUID documentId, boolean operational) {
        transaction().executeWithoutResult(status -> {
            var document = documents.findById(documentId).orElseThrow();
            if (operational) publisher.schedule(fixture.companyId(), document, null, SyncOperation.CONFIRMAR);
            else publisher.scheduleIfUnpublished(fixture.companyId(), document, null);
        });
    }

    private void assertUnpaidBusinessDataUnchanged(UUID documentId) {
        assertThat(jdbc.queryForObject("select estado from documento where id=?", String.class, documentId))
                .isEqualTo("PENDIENTE");
        assertThat(jdbc.queryForObject("select total from documento where id=?", BigDecimal.class, documentId))
                .isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("select count(*) from documento_linea where documento_id=?",
                Integer.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from documento_pago where documento_id=?",
                Integer.class, documentId)).isZero();
    }

    private UUID createUnpublishedInvoice() {
        return Objects.requireNonNull(transaction().execute(status ->
                documents.save(newDocument(CommercialDocumentType.FACTURA_VENTA)).getId()));
    }

    private UUID createPublishedInvoice() {
        return Objects.requireNonNull(transaction().execute(status -> {
            var document = documents.save(newDocument(CommercialDocumentType.FACTURA_VENTA));
            publisher.schedule(fixture.companyId(), document, null, SyncOperation.CONFIRMAR);
            return document.getId();
        }));
    }

    private CommercialDocument newDocument(CommercialDocumentType type) {
        var document = new CommercialDocument(fixture.storeId(), fixture.warehouseId(), type,
                DATE, fixture.userId(), BigDecimal.ZERO);
        document.setParties(fixture.customerId(), null, null);
        document.addLine(new DocumentLine(document, fixture.productId(), 1, 1, "P1", "Synthetic product",
                "VENTA", new BigDecimal("10.000"), BigDecimal.ZERO, false, "IGIC", BigDecimal.ZERO));
        document.confirm("D-" + document.getId().toString().replace("-", "").substring(0, 24),
                fixture.userId(), NOW, false);
        return document;
    }

    private void addPayment(CommercialDocument document, String amount) {
        int position = document.getPagos().size() + 1;
        var method = paymentMethods.findById(fixture.paymentMethodId()).orElseThrow();
        document.addPayment(new DocumentPayment(document, method, position, new BigDecimal(amount),
                position == 1, null, null, NOW));
        document.updatePaymentStatus();
    }

    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(15);
        return transaction;
    }

    private long revision(UUID documentId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select coalesce(max(source_revision),0) from documento_sync_revision where documento_id=?",
                Long.class, documentId));
    }

    private List<Event> events(UUID documentId) {
        return jdbc.query("""
                select (payload->>'sourceRevision')::bigint as revision, operacion, payload::text as payload
                  from sync_outbox where tipo_entidad='DOCUMENTO' and entidad_id=?
                 order by (payload->>'sourceRevision')::bigint
                """, (rs, index) -> new Event(rs.getLong("revision"), rs.getString("operacion"),
                        json(rs.getString("payload"))), documentId);
    }

    private void assertAbsent(UUID documentId) {
        assertThat(jdbc.queryForObject("select count(*) from documento where id=?", Integer.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from documento_linea where documento_id=?",
                Integer.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from documento_sync_revision where documento_id=?",
                Integer.class, documentId)).isZero();
        assertThat(revision(documentId)).isZero();
        assertThat(events(documentId)).isEmpty();
    }

    private Fixture createFixture() {
        UUID company = UUID.randomUUID(), store = UUID.randomUUID(), warehouse = UUID.randomUUID();
        UUID role = UUID.randomUUID(), user = UUID.randomUUID(), customer = UUID.randomUUID();
        UUID family = UUID.randomUUID(), tax = UUID.randomUUID(), product = UUID.randomUUID();
        UUID method = UUID.randomUUID();
        String address = "{\"linea1\":\"CALLE TEST\",\"ciudad\":\"LAS PALMAS\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"LAS PALMAS\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                company, "TEST-" + company.toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                "Synthetic company", address);
        jdbc.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,
                    timezone,moneda,locale,codigo_tienda)
                values (?,?,?,cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES','001')
                """, store, company, "Synthetic store", address, "hash-" + store);
        jdbc.update("insert into rol(id,tienda_id,nombre,protegido) values (?,?,'ADMIN',true)", role, store);
        jdbc.update("""
                insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id,protegido)
                values (?,?,'ADMIN','ADMIN','test-only-hash',?,true)
                """, user, store, role);
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado) values (?,?,'GENERAL',true)", warehouse, store);
        jdbc.update("""
                insert into cliente(id,empresa_id,client_id,client_code_store_id,nombre_fiscal,tipo_documento,numero_documento)
                values (?,?,'C-001-000001',?,'SYNTHETIC CUSTOMER','PASAPORTE','TEST-CUSTOMER')
                """, customer, company, store);
        jdbc.update("insert into familia(id,tienda_id,nombre) values (?,?,'GENERAL')", family, store);
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values (?,?,0)", tax, store);
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,'PRODUCTO TEST')",
                product, store, family, tax);
        jdbc.update("insert into metodo_pago(id,empresa_id,nombre) values (?,?,'EFECTIVO')", method, company);
        fixture = new Fixture(company, store, warehouse, user, customer, product, method, null);
        var origin = documents.save(newDocument(CommercialDocumentType.ALBARAN_VENTA));
        return new Fixture(company, store, warehouse, user, customer, product, method, origin.getId());
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unexpected outbox JSON in test", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out coordinating test transactions");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test transactions", exception);
        }
    }

    private record Event(long revision, String operation, JsonNode payload) { }
    private record Fixture(UUID companyId, UUID storeId, UUID warehouseId, UUID userId, UUID customerId,
            UUID productId, UUID paymentMethodId, UUID originId) { }

    @TestConfiguration
    static class Configuration {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}

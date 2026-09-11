package com.tpverp.backend.document;

import static com.tpverp.backend.document.DocumentSyncRecoveryApi.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboxService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real local recovery transactions and SQL; the SaaS boundary never runs with a DB transaction held. */
@DataJpaTest(showSql = false, properties = "tpv.sync.document-recovery-enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, DocumentSyncRecoveryService.class, DocumentSyncRecoveryRepository.class,
        DocumentSyncPublisher.class, DocumentSyncRevisionRepository.class, DocumentSyncPayloadFactory.class,
        DocumentAttributionResolver.class, SyncOutboxService.class, DocumentSyncRecoveryPostgreSqlTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class DocumentSyncRecoveryPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "document_sync_recovery_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(3600);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 10);

    @Autowired DocumentSyncRecoveryService service;
    @Autowired DocumentSyncRecoveryRepository recovery;
    @Autowired DocumentSyncPublisher publisher;
    @Autowired CommercialDocumentRepository documents;
    @Autowired PaymentMethodRepository paymentMethods;
    @Autowired StoreRepository stores;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentOrganization organization;
    @MockitoBean DocumentSyncRecoveryClient central;
    @MockitoBean AuditService audit;
    @MockitoSpyBean SyncOutboxService outbox;
    private Fixture fixture;
    private Authentication admin;

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
    void prepareSyntheticStoreAndActiveAdministrator() {
        fixture = Objects.requireNonNull(transaction().execute(tx -> createFixture(null, "001")));
        transaction().executeWithoutResult(tx -> {
            var store = stores.findWithCompanyById(fixture.storeId()).orElseThrow();
            var user = users.findById(fixture.userId()).orElseThrow();
            assertThat(user.isActivo()).isTrue();
            admin = UsernamePasswordAuthenticationToken.authenticated(user, "test-only",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            when(organization.currentStore()).thenReturn(store);
            when(organization.currentCompany()).thenReturn(store.getEmpresa());
            when(organization.currentUser(admin)).thenReturn(user);
        });
        remote(expected -> expected.stream().map(value -> new RemoteRow(value.documentId(), "MISSING", null, null,
                "NOT_REQUESTED", false, null, null, null)).toList());
    }

    @AfterEach
    void resetOutbox() {
        reset(outbox);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!SCHEMA.matches("document_sync_recovery_[0-9a-f]{32}")) throw new IllegalStateException("Unexpected test schema");
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void previewSelectsOnlyUnpublishedOriginalsWithinInclusiveDatesCutoffAndCurrentStoreWithoutWritesOrHttp() {
        var otherStore = Objects.requireNonNull(transaction().execute(tx -> createFixture(fixture.companyId(), "002")));
        var otherCompany = Objects.requireNonNull(transaction().execute(tx -> createFixture(null, "001")));
        UUID firstDate = createDocument(fixture, DATE.minusDays(1), CREATED, false);
        UUID lastDate = createDocument(fixture, DATE, NOW, false);
        createDocument(fixture, DATE.minusDays(2), CREATED, false);
        createDocument(fixture, DATE.plusDays(1), CREATED, false);
        createDocument(fixture, DATE, NOW.plusSeconds(1), false);
        createDocument(fixture, DATE, CREATED, true);
        createDocument(otherStore, DATE, CREATED, false);
        createDocument(otherCompany, DATE, CREATED, false);
        UUID published = createDocument(fixture, DATE, CREATED, false);
        transaction().executeWithoutResult(tx -> publisher.schedule(fixture.companyId(),
                documents.findById(published).orElseThrow(), null, SyncOperation.CONFIRMAR));
        State before = state();

        var preview = service.preview(new PreviewRequest(new Scope(fixture.companyId(), fixture.storeId(),
                DATE.minusDays(1), DATE, null), null), admin);

        assertThat(preview.scope().createdBefore()).isEqualTo(NOW);
        assertThat(preview.documents()).extracting(Row::documentId).containsExactlyElementsOf(sorted(List.of(firstDate, lastDate)));
        assertThat(preview.documents()).allSatisfy(row -> {
            assertThat(row.problem()).isNull();
            assertThat(row.total()).isEqualTo("10.00");
            assertThat(row.currency()).isEqualTo("EUR");
        });
        assertThat(preview.hasMore()).isFalse();
        assertThat(preview.nextAfterId()).isNull();
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(central, audit);
    }

    @Test
    void previewUsesStableUuidPagesOfOneHundredAndReplayingTheCursorDoesNotWrite() {
        List<UUID> ids = Objects.requireNonNull(transaction().execute(tx -> {
            var pending = new ArrayList<CommercialDocument>();
            for (int i = 0; i < 101; i++) pending.add(newDocument(fixture, DATE, false));
            documents.saveAllAndFlush(pending);
            jdbc.update("update documento set creado_en=? where tienda_id=?", Timestamp.from(CREATED), fixture.storeId());
            return sorted(pending.stream().map(CommercialDocument::getId).toList());
        }));
        State before = state();

        var first = service.preview(new PreviewRequest(scope(), null), admin);
        assertThat(first.documents()).extracting(Row::documentId).containsExactlyElementsOf(ids.subList(0, 100));
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextAfterId()).isEqualTo(ids.get(99));
        var missingCutoff = new Scope(fixture.companyId(), fixture.storeId(), DATE, DATE, null);
        assertThatThrownBy(() -> service.preview(new PreviewRequest(missingCutoff, first.nextAfterId()), admin))
                .isInstanceOf(DocumentSyncRecoveryException.class).hasMessage("DOCUMENT_RECOVERY_INVALID_REQUEST");
        var secondRequest = new PreviewRequest(first.scope(), first.nextAfterId());
        var second = service.preview(secondRequest, admin);
        assertThat(second.documents()).extracting(Row::documentId).containsExactly(ids.getLast());
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextAfterId()).isNull();
        assertThat(service.preview(secondRequest, admin)).isEqualTo(second);
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(central, audit);
    }

    @Test
    void prepareCommitsReceiptsAtomicallyAndRepeatingTheSelectionDoesNotChangeBusinessOrCreateMoreEvents() {
        UUID first = createDocument(fixture, DATE, CREATED, false);
        UUID second = createDocument(fixture, DATE, CREATED, false);
        transaction().executeWithoutResult(tx -> {
            var document = documents.findById(first).orElseThrow();
            document.addPayment(new DocumentPayment(document, paymentMethods.findById(fixture.paymentMethodId()).orElseThrow(),
                    1, new BigDecimal("3.25"), true, null, null, CREATED));
            document.updatePaymentStatus();
            jdbc.update("""
                    insert into movimiento_stock(id,producto_id,almacen_id,usuario_id,documento_id,tipo,cantidad,creado_en)
                    values (?,?,?,?,?,'FACTURA_VENTA',-1,?)
                    """, UUID.randomUUID(), fixture.productId(), fixture.warehouseId(), fixture.userId(), first, Timestamp.from(CREATED));
        });
        var request = prepareRequest(List.of(first, second));
        State before = state();

        Prepared prepared = service.prepare(request, admin);

        assertThat(prepared.status()).isEqualTo("ENQUEUED");
        assertThat(prepared.documents()).extracting(Receipt::documentId).containsExactly(first, second);
        assertThat(prepared.documents()).allSatisfy(receipt -> {
            assertThat(receipt.eventId()).isNotNull();
            assertThat(receipt.sourceRevision()).isEqualTo("1");
            assertThat(receipt.outboxStatus()).isEqualTo("PENDIENTE");
            assertThat(recovery.receiptMatches(scope(), receipt)).isTrue();
        });
        assertThat(state().business()).isEqualTo(before.business());
        assertThat(state().revisions()).hasSize(2);
        assertThat(state().outbox()).hasSize(2);
        assertThat(jdbc.queryForObject("""
                select payload->>'estado' from sync_outbox where entidad_id=? and tipo_entidad='DOCUMENTO'
                """, String.class, first)).isEqualTo("PARCIAL");
        assertThat(jdbc.queryForObject("""
                select payload->'pagos'->0->>'importe' from sync_outbox where entidad_id=? and tipo_entidad='DOCUMENTO'
                """, String.class, first)).isEqualTo("3.25");
        State committed = state();

        assertThat(service.prepare(request, admin)).isEqualTo(prepared);
        assertThat(state()).isEqualTo(committed);
        verify(central, times(1)).status(eq(scope()), anyList());
        verify(audit, times(2)).record(eq("DOCUMENT_SYNC_RECOVERY_PREPARE"), eq(AuditResult.EXITO), anyMap());
    }

    @Test
    void secondOutboxFailureRollsBackBothInitialClaimsAndEventsWithoutChangingEitherOriginal() {
        UUID first = createDocument(fixture, DATE, CREATED, false);
        UUID second = createDocument(fixture, DATE, CREATED, false);
        State before = state();
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            entities.flush();
            if (attempts.incrementAndGet() == 2) {
                assertThat(state().outbox()).hasSize(2);
                assertThat(state().revisions()).hasSize(2);
                throw new IllegalStateException("forced-second-recovery-outbox-failure");
            }
            return result;
        }).when(outbox).enqueue(any());

        assertThatThrownBy(() -> service.prepare(prepareRequest(List.of(first, second)), admin))
                .isInstanceOf(IllegalStateException.class).hasMessage("forced-second-recovery-outbox-failure");

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(state()).isEqualTo(before);
        assertThat(recovery.published(first)).isFalse();
        assertThat(recovery.published(second)).isFalse();
        verify(audit).record(eq("DOCUMENT_SYNC_RECOVERY_PREPARE"), eq(AuditResult.FALLO), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROJECTED", "OTHER_INSTALLATION"})
    void existingCentralSourceRejectsPreparationBeforeAnyLocalEnqueue(String centralStatus) {
        UUID id = createDocument(fixture, DATE, CREATED, false);
        State before = state();
        remote(expected -> List.of(new RemoteRow(id, centralStatus, centralStatus.equals("PROJECTED") ? 7L : null,
                centralStatus.equals("PROJECTED") ? UUID.randomUUID() : null, "NOT_REQUESTED", false,
                centralStatus.equals("PROJECTED") ? Boolean.TRUE : null, null, null)));

        assertThatThrownBy(() -> service.prepare(prepareRequest(List.of(id)), admin))
                .isInstanceOf(DocumentSyncRecoveryException.class).hasMessage("DOCUMENT_RECOVERY_CENTRAL_SOURCE_CONFLICT");

        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(outbox);
        verify(audit).record(eq("DOCUMENT_SYNC_RECOVERY_PREPARE"), eq(AuditResult.FALLO), anyMap());
    }

    @Test
    void verifyAcceptsTheOriginalReceiptWhenItsLedgerIsRecordedAndTheProjectionHasANewerRevisionWithoutWriting() {
        UUID id = createDocument(fixture, DATE, CREATED, false);
        Receipt original = service.prepare(prepareRequest(List.of(id)), admin).documents().getFirst();
        transaction().executeWithoutResult(tx -> publisher.schedule(fixture.companyId(),
                documents.findById(id).orElseThrow(), null, SyncOperation.ACTUALIZAR));
        Receipt latest = recovery.latestReceipt(scope(), id);
        assertThat(latest.sourceRevision()).isEqualTo("2");
        assertThat(latest.eventId()).isNotEqualTo(original.eventId());
        remote(expected -> {
            assertThat(expected).containsExactly(new Expectation(id, original.eventId(), 1L));
            return List.of(new RemoteRow(id, "PROJECTED", 2L, latest.eventId(), "PROJECTED", true, true, "10.00", "EUR"));
        });
        clearInvocations(audit);
        State before = state();

        Verified verified = service.verify(new VerifyRequest(scope(), List.of(original)), admin);

        assertThat(verified.complete()).isTrue();
        assertThat(verified.documents()).containsExactly(new VerifiedRow(id, original.eventId(), "1", true, true, "PROJECTED"));
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @CsvSource({"PROJECTED,false,true,REVISION_NOT_VERIFIED", "IGNORED,false,true,IGNORED",
            "PROJECTED,true,false,CUSTOMER_BINDING_MISSING"})
    void verifyDoesNotClaimCompletionWithoutRecordedRevisionProjectedEventAndCustomerBinding(
            String eventStatus, boolean ledgerRecorded, boolean customerLinked, String result) {
        UUID id = createDocument(fixture, DATE, CREATED, false);
        Receipt original = service.prepare(prepareRequest(List.of(id)), admin).documents().getFirst();
        // A caller's transport status is not evidence that SaaS projected this precise revision.
        Receipt allegedSent = new Receipt(id, original.eventId(), original.sourceRevision(), "ENVIADO");
        remote(expected -> List.of(new RemoteRow(id, "PROJECTED", 1L, original.eventId(), eventStatus,
                ledgerRecorded, customerLinked, "10.00", "EUR")));
        clearInvocations(audit);
        State before = state();

        Verified verified = service.verify(new VerifyRequest(scope(), List.of(allegedSent)), admin);

        assertThat(verified.complete()).isFalse();
        assertThat(verified.documents()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(result);
            assertThat(row.projected()).isEqualTo(eventStatus.equals("PROJECTED") && ledgerRecorded);
        });
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(audit);
    }

    @Test
    void verifyRejectsAnUnknownEventOrAlteredRevisionBeforeCallingSaas() {
        UUID id = createDocument(fixture, DATE, CREATED, false);
        Receipt original = service.prepare(prepareRequest(List.of(id)), admin).documents().getFirst();
        clearInvocations(central, audit);
        State before = state();
        var forged = List.of(new Receipt(id, UUID.randomUUID(), "1", "ENVIADO"),
                new Receipt(id, original.eventId(), "2", "ENVIADO"));

        for (Receipt receipt : forged) {
            assertThatThrownBy(() -> service.verify(new VerifyRequest(scope(), List.of(receipt)), admin))
                    .isInstanceOf(DocumentSyncRecoveryException.class).hasMessage("DOCUMENT_RECOVERY_RECEIPT_MISMATCH");
        }

        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(central, audit);
    }

    @Test
    void scopeAndAdministratorChecksRejectForeignDocumentsAndUnboundOrNonAdminUsers() {
        UUID local = createDocument(fixture, DATE, CREATED, false);
        var foreign = Objects.requireNonNull(transaction().execute(tx -> createFixture(fixture.companyId(), "002")));
        UUID foreignDocument = createDocument(foreign, DATE, CREATED, false);
        State before = state();
        var foreignScope = new Scope(fixture.companyId(), foreign.storeId(), DATE, DATE, NOW);
        var foreignCompany = new Scope(UUID.randomUUID(), fixture.storeId(), DATE, DATE, NOW);

        assertThatThrownBy(() -> service.preview(new PreviewRequest(foreignScope, null), admin))
                .isInstanceOf(AccessDeniedException.class).hasMessage("DOCUMENT_RECOVERY_SCOPE_DENIED");
        assertThatThrownBy(() -> service.prepare(new PrepareRequest(foreignCompany, List.of(local), "Synthetic test"), admin))
                .isInstanceOf(AccessDeniedException.class).hasMessage("DOCUMENT_RECOVERY_SCOPE_DENIED");
        assertThatThrownBy(() -> service.prepare(prepareRequest(List.of(foreignDocument)), admin))
                .isInstanceOf(DocumentSyncRecoveryException.class).hasMessage("DOCUMENT_RECOVERY_SELECTION_CHANGED");
        Authentication cashier = UsernamePasswordAuthenticationToken.authenticated("cashier", "test-only",
                List.of(new SimpleGrantedAuthority("ROLE_CASHIER")));
        assertThatThrownBy(() -> service.preview(new PreviewRequest(scope(), null), cashier))
                .isInstanceOf(AccessDeniedException.class).hasMessage("DOCUMENT_RECOVERY_ADMIN_REQUIRED");
        assertThatThrownBy(() -> service.prepare(prepareRequest(List.of(local)), cashier))
                .isInstanceOf(AccessDeniedException.class).hasMessage("DOCUMENT_RECOVERY_ADMIN_REQUIRED");
        assertThatThrownBy(() -> service.verify(new VerifyRequest(scope(), List.of()), cashier))
                .isInstanceOf(AccessDeniedException.class).hasMessage("DOCUMENT_RECOVERY_ADMIN_REQUIRED");
        doThrow(new IllegalStateException("message.organization.authenticated_user_disabled"))
                .when(organization).currentUser(admin);
        assertThatThrownBy(() -> service.preview(new PreviewRequest(scope(), null), admin))
                .isInstanceOf(IllegalStateException.class).hasMessage("message.organization.authenticated_user_disabled");

        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(central, outbox);
    }

    private void remote(Function<List<Expectation>, List<RemoteRow>> response) {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("remote preflight/verification must not retain a local DB transaction").isFalse();
            Scope requested = invocation.getArgument(0);
            assertThat(requested.companyId()).isEqualTo(fixture.companyId());
            assertThat(requested.storeId()).isEqualTo(fixture.storeId());
            return response.apply(invocation.getArgument(1));
        }).when(central).status(any(), anyList());
    }

    private Scope scope() { return new Scope(fixture.companyId(), fixture.storeId(), DATE, DATE, NOW); }
    private PrepareRequest prepareRequest(List<UUID> ids) { return new PrepareRequest(scope(), ids, "Synthetic recovery test"); }
    private static List<UUID> sorted(List<UUID> ids) { return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList(); }

    private UUID createDocument(Fixture owner, LocalDate date, Instant created, boolean draft) {
        return Objects.requireNonNull(transaction().execute(tx -> {
            var document = documents.saveAndFlush(newDocument(owner, date, draft));
            jdbc.update("update documento set creado_en=? where id=?", Timestamp.from(created), document.getId());
            return document.getId();
        }));
    }

    private CommercialDocument newDocument(Fixture owner, LocalDate date, boolean draft) {
        var document = new CommercialDocument(owner.storeId(), owner.warehouseId(), CommercialDocumentType.FACTURA_VENTA,
                date, owner.userId(), BigDecimal.ZERO);
        document.setParties(owner.customerId(), null, null);
        document.addLine(new DocumentLine(document, owner.productId(), 1, 1, "P1", "Synthetic product",
                "VENTA", new BigDecimal("10.000"), BigDecimal.ZERO, false, "IGIC", BigDecimal.ZERO));
        if (!draft) document.confirm("D-" + document.getId().toString().replace("-", "").substring(0, 24),
                owner.userId(), CREATED, false);
        return document;
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setTimeout(30);
        return tx;
    }

    private State state() {
        var business = new BusinessState(
                jdbc.queryForList("select to_jsonb(d)::text from documento d where tienda_id=? order by id", String.class, fixture.storeId()),
                jdbc.queryForList("""
                        select to_jsonb(l)::text from documento_linea l join documento d on d.id=l.documento_id
                         where d.tienda_id=? order by l.id
                        """, String.class, fixture.storeId()),
                jdbc.queryForList("""
                        select to_jsonb(p)::text from documento_pago p join documento d on d.id=p.documento_id
                         where d.tienda_id=? order by p.id
                        """, String.class, fixture.storeId()),
                jdbc.queryForList("select to_jsonb(m)::text from movimiento_stock m where almacen_id=? order by id",
                        String.class, fixture.warehouseId()),
                jdbc.queryForList("select to_jsonb(e)::text from existencia e where almacen_id=? order by id",
                        String.class, fixture.warehouseId()));
        return new State(business, jdbc.queryForList("""
                select to_jsonb(r)::text from documento_sync_revision r join documento d on d.id=r.documento_id
                 where d.tienda_id=? order by r.documento_id
                """, String.class, fixture.storeId()),
                jdbc.queryForList("select to_jsonb(o)::text from sync_outbox o where tienda_id=? order by event_id",
                        String.class, fixture.storeId()));
    }

    private Fixture createFixture(UUID existingCompany, String storeCode) {
        UUID company = existingCompany == null ? UUID.randomUUID() : existingCompany;
        UUID store = UUID.randomUUID(), warehouse = UUID.randomUUID(), role = UUID.randomUUID(), user = UUID.randomUUID();
        UUID customer = UUID.randomUUID(), family = UUID.randomUUID(), tax = UUID.randomUUID(), product = UUID.randomUUID();
        UUID method = UUID.randomUUID();
        String address = "{\"linea1\":\"CALLE TEST\",\"ciudad\":\"LAS PALMAS\",\"codigoPostal\":\"35001\","
                + "\"provincia\":\"LAS PALMAS\",\"pais\":\"ES\"}";
        if (existingCompany == null) jdbc.update(
                "insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                company, "TEST-" + company.toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT), "Synthetic company", address);
        jdbc.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda)
                values (?,?,?,cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES',?)
                """, store, company, "Synthetic store", address, "hash-" + store, storeCode);
        jdbc.update("insert into rol(id,tienda_id,nombre,protegido) values (?,?,'ADMIN',true)", role, store);
        jdbc.update("""
                insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id,protegido)
                values (?,?,'ADMIN','ADMIN','test-only-hash',?,true)
                """, user, store, role);
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado) values (?,?,'GENERAL',true)", warehouse, store);
        jdbc.update("""
                insert into cliente(id,empresa_id,client_id,client_code_store_id,nombre_fiscal,tipo_documento,numero_documento)
                values (?,?,?,?,'SYNTHETIC CUSTOMER','PASAPORTE',?)
                """, customer, company, "C-" + storeCode + "-000001", store,
                "TEST-" + customer.toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT));
        jdbc.update("insert into familia(id,tienda_id,nombre) values (?,?,'GENERAL')", family, store);
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values (?,?,0)", tax, store);
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,'PRODUCTO TEST')",
                product, store, family, tax);
        jdbc.update("insert into metodo_pago(id,empresa_id,nombre) values (?,?,?)", method, company, "TEST-" + storeCode);
        jdbc.update("insert into existencia(id,producto_id,almacen_id,cantidad) values (?,?,?,23.500)",
                UUID.randomUUID(), product, warehouse);
        return new Fixture(company, store, warehouse, user, customer, product, method);
    }

    private record Fixture(UUID companyId, UUID storeId, UUID warehouseId, UUID userId, UUID customerId,
            UUID productId, UUID paymentMethodId) { }
    private record BusinessState(List<String> documents, List<String> lines, List<String> payments,
            List<String> stockMovements, List<String> stockLevels) { }
    private record State(BusinessState business, List<String> revisions, List<String> outbox) { }

    @TestConfiguration
    static class Configuration {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}

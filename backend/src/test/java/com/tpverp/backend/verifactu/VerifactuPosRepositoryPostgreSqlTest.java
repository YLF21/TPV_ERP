package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        VerifactuAdminReadRepository.class,
        VerifactuAdminReviewReadRepository.class,
        FiscalSubmissionQueueService.class,
        VerifactuDefectClassifier.class,
        VerifactuPosRepositoryPostgreSqlTest.ClockConfiguration.class})
class VerifactuPosRepositoryPostgreSqlTest {

    private static final String URL = environment(
            "TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    private static final String USER = environment("TPV_TEST_DB_USERNAME", "tpv_erp_test");
    private static final String PASSWORD = environment("TPV_TEST_DB_PASSWORD", "admin");
    private static final String SCHEMA =
            "verifactu_pos_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant BASE_TIME = Instant.parse("2026-07-21T12:00:00Z");
    private static final List<FiscalSubmissionStatus> POS_QUEUE_STATUSES = List.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIANDO,
            FiscalSubmissionStatus.ENVIADO,
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.DEFECTUOSO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private FiscalSubmissionStateRepository states;
    @Autowired private VerifactuAdminReadRepository adminReads;
    @Autowired private VerifactuAdminReviewReadRepository reviewReads;
    @Autowired private FiscalSubmissionQueueService queue;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private CurrentOrganization organization;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void executesTerminalScopedQueueAgainstPostgreSqlWithStableOrderingAndCounts() {
        var fixture = insertFixture();

        var targetQueue = states.findPosQueue(
                fixture.firstCompanyId(),
                fixture.targetStoreId(),
                fixture.targetTerminalId(),
                POS_QUEUE_STATUSES,
                PageRequest.of(0, 50));

        assertThat(targetQueue)
                .extracting(VerifactuPosQueueRecord::documentNumber)
                .containsExactlyElementsOf(fixture.expectedTargetOrder());
        assertThat(targetQueue)
                .extracting(VerifactuPosQueueRecord::submissionStatus)
                .containsExactly(
                        FiscalSubmissionStatus.RECHAZADO,
                        FiscalSubmissionStatus.ENVIANDO,
                        FiscalSubmissionStatus.PENDIENTE);
        assertThat(targetQueue)
                .extracting(VerifactuPosQueueRecord::documentNumber)
                .doesNotContain(
                        fixture.acceptedDocumentNumber(),
                        fixture.correctedDocumentNumber(),
                        fixture.nullTerminalDocumentNumber(),
                        fixture.otherTerminalDocumentNumber(),
                        fixture.otherStoreDocumentNumber(),
                        fixture.otherCompanyDocumentNumber());

        assertThat(states.findPosQueue(
                fixture.firstCompanyId(),
                fixture.targetStoreId(),
                fixture.targetTerminalId(),
                POS_QUEUE_STATUSES,
                PageRequest.of(0, 2)))
                .extracting(VerifactuPosQueueRecord::documentNumber)
                .containsExactlyElementsOf(fixture.expectedTargetOrder().subList(0, 2));

        assertThat(states.countPosQueueByStatusIn(
                fixture.firstCompanyId(), fixture.targetStoreId(), fixture.targetTerminalId(),
                List.of(FiscalSubmissionStatus.PENDIENTE, FiscalSubmissionStatus.ENVIADO)))
                .isEqualTo(1);
        assertThat(states.countPosQueueByStatusIn(
                fixture.firstCompanyId(), fixture.targetStoreId(), fixture.targetTerminalId(),
                List.of(FiscalSubmissionStatus.ENVIANDO)))
                .isEqualTo(1);
        assertThat(states.countPosQueueByStatusIn(
                fixture.firstCompanyId(), fixture.targetStoreId(), fixture.targetTerminalId(),
                List.of(
                        FiscalSubmissionStatus.RECHAZADO,
                        FiscalSubmissionStatus.DEFECTUOSO,
                        FiscalSubmissionStatus.ACEPTADO_CON_ERRORES)))
                .isEqualTo(1);
        assertThat(states.countPosQueueByStatusIn(
                fixture.firstCompanyId(), fixture.targetStoreId(), fixture.targetTerminalId(),
                List.of(FiscalSubmissionStatus.ACEPTADO, FiscalSubmissionStatus.SUBSANADO)))
                .isEqualTo(2);

        assertThat(states.findPosQueue(
                fixture.firstCompanyId(), fixture.targetStoreId(), fixture.otherTerminalId(),
                POS_QUEUE_STATUSES,
                PageRequest.of(0, 50)))
                .extracting(VerifactuPosQueueRecord::documentNumber)
                .containsExactly(fixture.otherTerminalDocumentNumber());
        assertThat(states.findPosQueue(
                fixture.firstCompanyId(), fixture.otherStoreId(), fixture.otherStoreTerminalId(),
                POS_QUEUE_STATUSES,
                PageRequest.of(0, 50)))
                .extracting(VerifactuPosQueueRecord::documentNumber)
                .containsExactly(fixture.otherStoreDocumentNumber());
        assertThat(states.findPosQueue(
                fixture.secondCompanyId(), fixture.otherCompanyStoreId(),
                fixture.otherCompanyTerminalId(), POS_QUEUE_STATUSES, PageRequest.of(0, 50)))
                .extracting(VerifactuPosQueueRecord::documentNumber)
                .containsExactly(fixture.otherCompanyDocumentNumber());
    }

    @Test
    void executesStoreScopedAdminPaginationFiltersAndCountsWithoutCrossingScopes() {
        var fixture = insertFixture();

        var firstPage = adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                null, null, null, null, null, null, 0, 2);

        assertThat(firstPage.items())
                .extracting(VerifactuAdminSubmissionView::documentNumber)
                .containsExactly(
                        fixture.otherTerminalDocumentNumber(),
                        fixture.nullTerminalDocumentNumber());
        assertThat(firstPage.totalElements()).isEqualTo(7);
        assertThat(firstPage.totalPages()).isEqualTo(4);

        var secondPage = adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                null, null, null, null, null, null, 1, 2);
        assertThat(secondPage.items())
                .extracting(VerifactuAdminSubmissionView::documentNumber)
                .containsExactly(
                        fixture.acceptedDocumentNumber(),
                        fixture.correctedDocumentNumber())
                .doesNotContainAnyElementsOf(firstPage.items().stream()
                        .map(VerifactuAdminSubmissionView::documentNumber)
                        .toList());

        var rejected = adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                BASE_TIME.plusSeconds(20), BASE_TIME.plusSeconds(40),
                FiscalSubmissionStatus.RECHAZADO, FiscalDocumentType.F2,
                FiscalRecordOperation.ALTA, "TARGET", 0, 25);
        assertThat(rejected.items())
                .extracting(VerifactuAdminSubmissionView::documentNumber)
                .containsExactly(fixture.expectedTargetOrder().getFirst());
        assertThat(rejected.items().getFirst().errorCode()).isEqualTo("VF-TEST");

        var literalWildcard = adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                null, null, null, null, null, "_", 0, 25);
        assertThat(literalWildcard.totalElements()).isZero();
        assertThat(adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                null, null, null, null, null, "%", 0, 25).totalElements()).isZero();
        assertThat(adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                null, null, null, null, null, "\\", 0, 25).totalElements()).isZero();

        var exactDateWindow = adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                BASE_TIME.plusSeconds(20), BASE_TIME.plusSeconds(30),
                null, null, null, null, 0, 25);
        assertThat(exactDateWindow.items())
                .extracting(VerifactuAdminSubmissionView::sequence)
                .containsExactly(5L, 4L);

        assertThat(adminReads.countByStatus(
                fixture.firstCompanyId(), fixture.targetStoreId()))
                .containsEntry(FiscalSubmissionStatus.PENDIENTE, 3L)
                .containsEntry(FiscalSubmissionStatus.ACEPTADO, 1L)
                .doesNotContainEntry(FiscalSubmissionStatus.PENDIENTE, 4L);
        assertThat(adminReads.findOldestPendingAt(
                fixture.firstCompanyId(), fixture.targetStoreId()))
                .isEqualTo(BASE_TIME.plusSeconds(20));

        var siblingStore = adminReads.findSubmissions(
                fixture.firstCompanyId(), fixture.otherStoreId(),
                null, null, null, null, null, null, 0, 25);
        assertThat(siblingStore.items())
                .extracting(VerifactuAdminSubmissionView::documentNumber)
                .containsExactly(fixture.otherStoreDocumentNumber());

        var otherCompany = adminReads.findSubmissions(
                fixture.secondCompanyId(), fixture.otherCompanyStoreId(),
                null, null, null, null, null, null, 0, 25);
        assertThat(otherCompany.items())
                .extracting(VerifactuAdminSubmissionView::documentNumber)
                .containsExactly(fixture.otherCompanyDocumentNumber());
    }

    @Test
    void executesScopedDefectiveAndAttemptReadsWithoutSelectingOtherOrganizations() {
        var fixture = insertFixture();

        var targetDefective = reviewReads.findDefectiveRecords(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                null, null, FiscalSubmissionStatus.RECHAZADO,
                FiscalDocumentType.F2, FiscalRecordOperation.ALTA,
                "TARGET", 0, 25);
        assertThat(targetDefective.items())
                .extracting(VerifactuAdminDefectiveRecordView::documentNumber)
                .containsExactly(fixture.rejectedDocumentNumber());
        assertThat(targetDefective.totalElements()).isEqualTo(1);

        var siblingDefective = reviewReads.findDefectiveRecords(
                fixture.firstCompanyId(), fixture.otherStoreId(),
                null, null, null, null, null, null, 0, 25);
        assertThat(siblingDefective.items())
                .extracting(VerifactuAdminDefectiveRecordView::recordId)
                .containsExactly(fixture.otherStoreRecordId());
        var otherCompanyDefective = reviewReads.findDefectiveRecords(
                fixture.secondCompanyId(), fixture.otherCompanyStoreId(),
                null, null, null, null, null, null, 0, 25);
        assertThat(otherCompanyDefective.items())
                .extracting(VerifactuAdminDefectiveRecordView::recordId)
                .containsExactly(fixture.otherCompanyRecordId());

        assertThat(reviewReads.recordExists(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                fixture.rejectedRecordId())).isTrue();
        assertThat(reviewReads.recordExists(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                fixture.otherStoreRecordId())).isFalse();

        var firstAttemptPage = reviewReads.findAttempts(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                fixture.rejectedRecordId(), 0, 1);
        var secondAttemptPage = reviewReads.findAttempts(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                fixture.rejectedRecordId(), 1, 1);
        assertThat(firstAttemptPage.totalElements()).isEqualTo(2);
        assertThat(firstAttemptPage.totalPages()).isEqualTo(2);
        assertThat(firstAttemptPage.items()).hasSize(1);
        assertThat(firstAttemptPage.items().getFirst().hasTechnicalDetail()).isTrue();
        assertThat(secondAttemptPage.items()).hasSize(1);
        assertThat(secondAttemptPage.items().getFirst().attemptId())
                .isNotEqualTo(firstAttemptPage.items().getFirst().attemptId());
        assertThat(reviewReads.findAttempts(
                fixture.firstCompanyId(), fixture.targetStoreId(),
                fixture.otherStoreRecordId(), 0, 25).totalElements()).isZero();
        assertThat(reviewReads.findLastAttempt(
                fixture.firstCompanyId(), fixture.targetStoreId()))
                .isEqualTo(new VerifactuAdminDiagnosticEvent(
                        BASE_TIME.plusSeconds(32), FiscalSubmissionStatus.RECHAZADO));
    }

    @Test
    void manualRetryUsesPostgreSqlLockVersionAndOrganizationScope() {
        var fixture = insertFixture();
        jdbc.update(
                "update estado_envio_fiscal set estado = 'ENVIADO' where registro_id = ?",
                fixture.rejectedRecordId());
        var company = org.mockito.Mockito.mock(Company.class);
        var store = org.mockito.Mockito.mock(Store.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(company.getId()).thenReturn(fixture.firstCompanyId());
        when(store.getId()).thenReturn(fixture.targetStoreId());

        var claimed = queue.claimForManualRetry(fixture.rejectedRecordId(), 0);

        assertThat(claimed.state().getStatus()).isEqualTo(FiscalSubmissionStatus.ENVIANDO);
        assertThatThrownBy(() -> queue.claimForManualRetry(fixture.otherStoreRecordId(), 0))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Registro fiscal no encontrado");
    }

    private Fixture insertFixture() {
        var installationId = UUID.randomUUID();
        insertInstallation(installationId);

        var firstCompanyId = UUID.randomUUID();
        var secondCompanyId = UUID.randomUUID();
        insertCompany(firstCompanyId, "B12345674", "Empresa Uno");
        insertCompany(secondCompanyId, "B87654321", "Empresa Dos");

        var targetStore = insertStore(firstCompanyId, "001", "Tienda Objetivo", "target");
        var otherStore = insertStore(firstCompanyId, "002", "Tienda Hermana", "sibling");
        var otherCompanyStore = insertStore(
                secondCompanyId, "001", "Tienda Otra Empresa", "other-company");

        var targetTerminalId = insertTerminal(targetStore.storeId(), "CAJA-1");
        var otherTerminalId = insertTerminal(targetStore.storeId(), "CAJA-2");
        var otherStoreTerminalId = insertTerminal(otherStore.storeId(), "CAJA-1");
        var otherCompanyTerminalId = insertTerminal(otherCompanyStore.storeId(), "CAJA-1");

        var firstChainId = insertChain(firstCompanyId, installationId);
        var firstChainRecords = new ArrayList<UUID>();

        UUID accepted = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, targetStore,
                targetTerminalId, 1, FiscalSubmissionStatus.ACEPTADO,
                BASE_TIME.plusSeconds(50), previousHash(1));
        firstChainRecords.add(accepted);

        UUID corrected = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, targetStore,
                targetTerminalId, 2, FiscalSubmissionStatus.SUBSANADO,
                BASE_TIME.plusSeconds(40), previousHash(2));
        firstChainRecords.add(corrected);

        UUID rejected = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, targetStore,
                targetTerminalId, 3, FiscalSubmissionStatus.RECHAZADO,
                BASE_TIME.plusSeconds(30), previousHash(3));
        firstChainRecords.add(rejected);

        UUID pending = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, targetStore,
                targetTerminalId, 4, FiscalSubmissionStatus.PENDIENTE,
                BASE_TIME.plusSeconds(20), previousHash(4));
        firstChainRecords.add(pending);

        UUID sending = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, targetStore,
                targetTerminalId, 5, FiscalSubmissionStatus.ENVIANDO,
                BASE_TIME.plusSeconds(20), previousHash(5));
        firstChainRecords.add(sending);

        UUID nullTerminal = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, targetStore,
                null, 6, FiscalSubmissionStatus.PENDIENTE,
                BASE_TIME.plusSeconds(60), previousHash(6));
        firstChainRecords.add(nullTerminal);

        UUID otherTerminal = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, targetStore,
                otherTerminalId, 7, FiscalSubmissionStatus.PENDIENTE,
                BASE_TIME.plusSeconds(70), previousHash(7));
        firstChainRecords.add(otherTerminal);

        UUID otherStoreRecord = insertFiscalRecord(
                firstChainId, firstCompanyId, installationId, otherStore,
                otherStoreTerminalId, 8, FiscalSubmissionStatus.RECHAZADO,
                BASE_TIME.plusSeconds(80), previousHash(8));
        firstChainRecords.add(otherStoreRecord);
        updateChainHead(firstChainId, firstChainRecords.getLast(), 8);

        var secondChainId = insertChain(secondCompanyId, installationId);
        UUID otherCompanyRecord = insertFiscalRecord(
                secondChainId, secondCompanyId, installationId, otherCompanyStore,
                otherCompanyTerminalId, 1, FiscalSubmissionStatus.RECHAZADO,
                BASE_TIME.plusSeconds(90), previousHash(1));
        updateChainHead(secondChainId, otherCompanyRecord, 1);

        insertAttempt(
                rejected, BASE_TIME.plusSeconds(31), FiscalSubmissionStatus.ENVIADO,
                null, null, "<Registro>dato fiscal</Registro>", null);
        insertAttempt(
                rejected, BASE_TIME.plusSeconds(32), FiscalSubmissionStatus.RECHAZADO,
                "VF-TEST", "<script>dato sensible</script>", null,
                "<Respuesta>dato sensible</Respuesta>");
        insertAttempt(
                otherStoreRecord, BASE_TIME.plusSeconds(81), FiscalSubmissionStatus.RECHAZADO,
                "VF-OTHER", "otro comercio", null, "respuesta ajena");
        insertAttempt(
                otherCompanyRecord, BASE_TIME.plusSeconds(91), FiscalSubmissionStatus.RECHAZADO,
                "VF-COMPANY", "otra empresa", null, "respuesta ajena");

        jdbc.execute("set constraints all immediate");

        return new Fixture(
                firstCompanyId,
                secondCompanyId,
                targetStore.storeId(),
                otherStore.storeId(),
                otherCompanyStore.storeId(),
                targetTerminalId,
                otherTerminalId,
                otherStoreTerminalId,
                otherCompanyTerminalId,
                List.of(
                        documentNumber(targetStore, 3),
                        documentNumber(targetStore, 5),
                        documentNumber(targetStore, 4)),
                documentNumber(targetStore, 1),
                documentNumber(targetStore, 2),
                documentNumber(targetStore, 6),
                documentNumber(targetStore, 7),
                documentNumber(otherStore, 8),
                documentNumber(otherCompanyStore, 1),
                rejected,
                otherStoreRecord,
                otherCompanyRecord,
                documentNumber(targetStore, 3));
    }

    private void insertInstallation(UUID installationId) {
        jdbc.update("""
                insert into instalacion (id, referencia, public_key, creada_en, demo_hasta)
                values (?, 'VF-POS-TEST', 'public-key', ?, ?)
                """,
                installationId,
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-01-31T00:00:00Z")));
    }

    private void insertCompany(UUID companyId, String taxId, String name) {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, ?, ?, cast(? as jsonb))
                """, companyId, taxId, name, address());
    }

    private StoreFixture insertStore(
            UUID companyId, String storeCode, String name, String uniqueSuffix) {
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        jdbc.update("""
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values (?, ?, ?, ?, cast(? as jsonb), ?, 'Atlantic/Canary', 'EUR', 'es-ES')
                """, storeId, companyId, storeCode, name, address(), "hash-" + uniqueSuffix);
        jdbc.update("insert into rol (id, tienda_id, nombre) values (?, ?, 'VENTA')",
                roleId, storeId);
        jdbc.update("""
                insert into usuario (
                    id, tienda_id, nombre, user_name, password_hash, rol_id)
                values (?, ?, 'TEST', ?, 'hash', ?)
                """, userId, storeId, "Test " + uniqueSuffix, roleId);
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """, warehouseId, storeId);
        return new StoreFixture(
                storeId, userId, warehouseId, uniqueSuffix.toUpperCase(Locale.ROOT));
    }

    private UUID insertTerminal(UUID storeId, String name) {
        var terminalId = UUID.randomUUID();
        jdbc.update("""
                insert into terminal (
                    id, tienda_id, nombre, tipo, activa, aprobada, credential_hash)
                values (?, ?, ?, 'TERMINAL_VENTA', true, true, 'hash')
                """, terminalId, storeId, name);
        return terminalId;
    }

    private UUID insertChain(UUID companyId, UUID installationId) {
        var chainId = UUID.randomUUID();
        jdbc.update("""
                insert into cadena_fiscal (
                    id, empresa_id, instalacion_id, ultima_secuencia, actualizada_en)
                values (?, ?, ?, 0, ?)
                """, chainId, companyId, installationId,
                java.sql.Timestamp.from(BASE_TIME));
        return chainId;
    }

    private UUID insertFiscalRecord(
            UUID chainId,
            UUID companyId,
            UUID installationId,
            StoreFixture store,
            UUID terminalId,
            long sequence,
            FiscalSubmissionStatus status,
            Instant updatedAt,
            String previousHash) {
        var documentId = UUID.randomUUID();
        var recordId = UUID.randomUUID();
        var number = documentNumber(store, sequence);
        jdbc.update("""
                insert into documento (
                    id, tienda_id, almacen_id, tipo, estado, numero, fecha,
                    creado_en, confirmado_en, creado_por, confirmado_por,
                    terminal_origen_id, total)
                values (?, ?, ?, 'TICKET', 'CONFIRMADO', ?, ?, ?, ?, ?, ?, ?, 10.00)
                """,
                documentId,
                store.storeId(),
                store.warehouseId(),
                number,
                LocalDate.of(2026, 7, 21),
                java.sql.Timestamp.from(updatedAt.minusSeconds(10)),
                java.sql.Timestamp.from(updatedAt.minusSeconds(5)),
                store.userId(),
                store.userId(),
                terminalId);
        jdbc.update("""
                insert into registro_fiscal (
                    id, cadena_id, empresa_id, instalacion_id, tienda_id,
                    documento_id, secuencia, operacion, tipo_documento_fiscal,
                    serie_numero, fecha_expedicion, generado_en, zona_horaria,
                    nif_emisor, cuota_total, importe_total, huella_anterior,
                    huella, hash_snapshot, snapshot, version_formato,
                    version_algoritmo, version_aplicacion)
                values (?, ?, ?, ?, ?, ?, ?, 'ALTA', 'F2', ?, ?, ?,
                    'Atlantic/Canary', 'B12345674', 1.73, 10.00, ?, ?, ?,
                    cast('{}' as jsonb), '1.0', 'SHA-256', 'TEST')
                """,
                recordId,
                chainId,
                companyId,
                installationId,
                store.storeId(),
                documentId,
                sequence,
                number,
                LocalDate.of(2026, 7, 21),
                java.sql.Timestamp.from(updatedAt.minusSeconds(5)),
                sequence == 1 ? null : previousHash,
                hash(sequence),
                hash(10_000 + sequence));
        jdbc.update("""
                insert into estado_envio_fiscal (
                    registro_id, estado, ultimo_error_codigo, ultimo_error, actualizado_en)
                values (?, ?, ?, ?, ?)
                """,
                recordId,
                status.name(),
                requiresError(status) ? "VF-TEST" : null,
                requiresError(status) ? "Detalle interno que no forma parte de la proyeccion" : null,
                java.sql.Timestamp.from(updatedAt));
        return recordId;
    }

    private void updateChainHead(UUID chainId, UUID lastRecordId, long sequence) {
        jdbc.update("""
                update cadena_fiscal
                set ultimo_registro_id = ?, ultima_huella = ?, ultima_secuencia = ?,
                    actualizada_en = ?
                where id = ?
                """,
                lastRecordId,
                hash(sequence),
                sequence,
                java.sql.Timestamp.from(BASE_TIME.plusSeconds(100)),
                chainId);
    }

    private void insertAttempt(
            UUID recordId,
            Instant attemptedAt,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            String requestXml,
            String responsePayload) {
        jdbc.update("""
                insert into intento_envio_fiscal (
                    id, registro_id, intentado_en, estado, error_codigo,
                    error, xml_enviado, respuesta)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                recordId,
                java.sql.Timestamp.from(attemptedAt),
                status.name(),
                errorCode,
                error,
                requestXml,
                responsePayload);
    }

    private static boolean requiresError(FiscalSubmissionStatus status) {
        return status == FiscalSubmissionStatus.RECHAZADO
                || status == FiscalSubmissionStatus.DEFECTUOSO
                || status == FiscalSubmissionStatus.ACEPTADO_CON_ERRORES;
    }

    private static String previousHash(long sequence) {
        return sequence == 1 ? null : hash(sequence - 1);
    }

    private static String hash(long value) {
        return "%064X".formatted(value);
    }

    private static String documentNumber(StoreFixture store, long sequence) {
        return "T-%s-%03d".formatted(store.documentPrefix(), sequence);
    }

    private static String address() {
        return """
                {
                  "linea1":"Calle Test",
                  "ciudad":"Las Palmas",
                  "codigoPostal":"35001",
                  "provincia":"Las Palmas",
                  "pais":"ES"
                }
                """;
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL", exception);
        }
    }

    private record StoreFixture(
            UUID storeId, UUID userId, UUID warehouseId, String documentPrefix) {
    }

    private record Fixture(
            UUID firstCompanyId,
            UUID secondCompanyId,
            UUID targetStoreId,
            UUID otherStoreId,
            UUID otherCompanyStoreId,
            UUID targetTerminalId,
            UUID otherTerminalId,
            UUID otherStoreTerminalId,
            UUID otherCompanyTerminalId,
            List<String> expectedTargetOrder,
            String acceptedDocumentNumber,
            String correctedDocumentNumber,
            String nullTerminalDocumentNumber,
            String otherTerminalDocumentNumber,
            String otherStoreDocumentNumber,
            String otherCompanyDocumentNumber,
            UUID rejectedRecordId,
            UUID otherStoreRecordId,
            UUID otherCompanyRecordId,
            String rejectedDocumentNumber) {
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(BASE_TIME.plusSeconds(600), java.time.ZoneOffset.UTC);
        }
    }
}

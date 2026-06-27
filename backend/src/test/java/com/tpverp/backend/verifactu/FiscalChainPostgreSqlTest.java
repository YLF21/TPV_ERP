package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FiscalRecordService.class, FiscalChainPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FiscalChainPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "tpv_erp_fiscal_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2027-01-02T09:15:30Z");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private FiscalRecordService service;
    @Autowired private FiscalChainRepository chains;
    @Autowired private FiscalRecordRepository records;
    @Autowired private FiscalSubmissionStateRepository states;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

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

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("truncate table instalacion, empresa cascade");
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void persistsJsonSnapshotPendingStateAndHeadAssociationThroughJpa() {
        var fixture = insertFixture(1);
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("numero", fixture.numbers().getFirst());
        snapshot.put("cliente", null);
        snapshot.put("lineas", List.of(Map.of(
                "codigo", "P-1", "cantidad", 1, "total", new BigDecimal("12.10"))));

        var saved = inNewTransaction(() -> service.register(
                command(fixture, fixture.documentIds().getFirst(), snapshot)));

        var persisted = inNewTransaction(() -> records.findById(saved.getId()).orElseThrow());
        var chain = inNewTransaction(() -> chains
                .findForUpdate(fixture.companyId(), fixture.installationId())
                .orElseThrow());
        var state = states.findById(saved.getId()).orElseThrow();

        assertThat(persisted.getSnapshot())
                .containsEntry("identificador", fixture.documentIds().getFirst().toString())
                .containsEntry("numero", fixture.numbers().getFirst())
                .containsEntry("clienteId", null);
        assertThat((List<?>) persisted.getSnapshot().get("lineas"))
                .isEmpty();
        assertThat(persisted.getSnapshotHash())
                .isEqualTo(new FiscalJsonHasher().hash(persisted.getSnapshot()));
        assertThat(new FiscalJsonHasher().hash(persisted.getSnapshot()))
                .isEqualTo(persisted.getSnapshotHash());
        assertThat(chain.getLastRecord().getId()).isEqualTo(saved.getId());
        assertThat(chain.previousHash()).isEqualTo(saved.getHash());
        assertThat(chain.nextSequence()).isEqualTo(2);
        assertThat(state.getStatus()).isEqualTo(FiscalSubmissionStatus.PENDIENTE);
        assertThat(jdbc.queryForObject("""
                select jsonb_typeof(snapshot)
                from registro_fiscal
                where id = ?
                """, String.class, saved.getId())).isEqualTo("object");
    }

    @Test
    void serializesTwentyConcurrentDocumentsIntoOneContinuousChain() throws Exception {
        var fixture = insertFixture(20);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<FiscalRecord>>();
            for (var index = 0; index < fixture.documentIds().size(); index++) {
                var documentId = fixture.documentIds().get(index);
                var number = fixture.numbers().get(index);
                futures.add(executor.submit(() -> {
                    start.await();
                    return inNewTransaction(() -> service.register(
                            command(fixture, documentId, Map.of("numero", number))));
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        var chainId = jdbc.queryForObject(
                "select id from cadena_fiscal", UUID.class);
        var persisted = records.findAllByChainIdOrderBySequence(chainId);

        assertThat(persisted).extracting(FiscalRecord::getSequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 20)
                        .boxed().toList());
        assertThat(persisted.getFirst().getPreviousHash()).isNull();
        for (var index = 1; index < persisted.size(); index++) {
            assertThat(persisted.get(index).getPreviousHash())
                    .isEqualTo(persisted.get(index - 1).getHash());
        }
        assertThat(states.findAll()).hasSize(20)
                .allMatch(state -> state.getStatus() == FiscalSubmissionStatus.PENDIENTE);
        assertThat(jdbc.queryForObject(
                "select ultima_secuencia from cadena_fiscal", Long.class)).isEqualTo(20L);
    }

    @Test
    void createsOneConfigurationForConcurrentLegalRegistrations() throws Exception {
        var fixture = insertFixture(2);
        jdbc.update(
                "delete from configuracion_verifactu where empresa_id = ?",
                fixture.companyId());
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = fixture.documentIds().stream()
                    .map(documentId -> executor.submit(() -> {
                        start.await();
                        return inNewTransaction(
                                () -> service.register(command(fixture, documentId, Map.of())));
                    }))
                    .toList();
            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(jdbc.queryForObject("""
                select count(*)
                from configuracion_verifactu
                where empresa_id = ?
                """, Integer.class, fixture.companyId())).isEqualTo(1);
        assertThat(records.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select ultima_secuencia from cadena_fiscal", Long.class)).isEqualTo(2L);
    }

    @Test
    void rejectsTheSameFiscalOperationForOneDocument() {
        var fixture = insertFixture(1);
        var documentId = fixture.documentIds().getFirst();
        var command = command(
                fixture, documentId, Map.of("numero", fixture.numbers().getFirst()));
        inNewTransaction(() -> service.register(command));

        assertThatThrownBy(() -> inNewTransaction(() -> service.register(command)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registrada");
        assertThat(records.count()).isEqualTo(1);
        assertThat(states.count()).isEqualTo(1);
    }

    @Test
    void databaseTriggersRejectFiscalRecordUpdatesAndDeletes() {
        var fixture = insertFixture(1);
        var saved = inNewTransaction(() -> service.register(command(
                fixture,
                fixture.documentIds().getFirst(),
                Map.of("numero", fixture.numbers().getFirst()))));

        assertFiscalMutationRejected(
                "update registro_fiscal set serie_numero = 'ALTERADO' where id = ?",
                saved.getId());
        assertFiscalMutationRejected(
                "delete from registro_fiscal where id = ?",
                saved.getId());
    }

    @Test
    void rollsBackChainRecordAndStateWhenTheOuterTransactionFails() {
        var fixture = insertFixture(1);

        assertThatThrownBy(() -> inNewTransaction(() -> {
            service.register(command(
                    fixture,
                    fixture.documentIds().getFirst(),
                    Map.of("numero", fixture.numbers().getFirst())));
            throw new IllegalStateException("fallo final");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("fallo final");

        assertThat(chains.count()).isZero();
        assertThat(records.count()).isZero();
        assertThat(states.count()).isZero();
    }

    @Test
    void persistsCancellationRelationToOriginalAlta() {
        var fixture = insertFixture(1);
        var documentId = fixture.documentIds().getFirst();
        var alta = inNewTransaction(() -> service.register(
                command(fixture, documentId, Map.of())));
        jdbc.update("""
                update documento
                set estado = 'ANULADO', anulado_en = ?, anulado_por = ?,
                    motivo_anulacion = 'ERROR'
                where id = ?
                """, java.sql.Timestamp.from(NOW), fixture.userId(), documentId);

        var cancellation = inNewTransaction(() -> service.register(
                command(
                        fixture, documentId, FiscalRecordOperation.ANULACION,
                        FiscalDocumentType.F2)));

        assertThat(jdbc.queryForObject("""
                select count(*)
                from registro_fiscal_relacion
                where registro_id = ? and relacionado_id = ? and tipo = 'ANULA'
                """, Integer.class, cancellation.getId(), alta.getId())).isEqualTo(1);
    }

    @Test
    void freezesCustomerFiscalDataBeforeLaterDatabaseChanges() {
        var fixture = insertFixture(1);
        var customerId = UUID.randomUUID();
        jdbc.update("""
                insert into cliente (
                    id, empresa_id, nombre_fiscal, tipo_documento,
                    numero_documento, direccion, codigo_postal, poblacion,
                    provincia, pais, tarifa, descuento, member_balance,
                    code_client, client_code_store_id)
                values (?, ?, 'Cliente Original', 'NIF', '12345678Z',
                    'Calle Original', '35001', 'Las Palmas',
                    'Las Palmas', 'ES', 'VENTA', 0, 0, 'C-001-000001', ?)
                """, customerId, fixture.companyId(), fixture.storeId());
        jdbc.update(
                "update documento set cliente_id = ? where id = ?",
                customerId, fixture.documentIds().getFirst());

        var saved = inNewTransaction(() -> service.register(
                command(fixture, fixture.documentIds().getFirst(), Map.of())));
        jdbc.update("""
                update cliente
                set nombre_fiscal = 'Cliente Alterado',
                    numero_documento = '87654321X',
                    direccion = 'Calle Alterada'
                where id = ?
                """, customerId);

        var persisted = inNewTransaction(
                () -> records.findById(saved.getId()).orElseThrow());
        var customer = (Map<?, ?>) persisted.getSnapshot().get("cliente");
        var address = (Map<?, ?>) customer.get("direccion");

        assertThat(customer.get("nombreFiscal")).isEqualTo("Cliente Original");
        assertThat(customer.get("numeroDocumento")).isEqualTo("12345678Z");
        assertThat(address.get("calle")).isEqualTo("Calle Original");
        assertThat(new FiscalJsonHasher().hash(persisted.getSnapshot()))
                .isEqualTo(persisted.getSnapshotHash());
    }

    private Fixture insertFixture(int documentCount) {
        var fixture = new Fixture(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ArrayList<>(),
                new ArrayList<>());
        jdbc.update("""
                insert into instalacion (
                    id, referencia, public_key, creada_en, demo_hasta)
                values (?, 'TEST-FISCAL', 'public-key', ?, ?)
                """,
                fixture.installationId(),
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-01-31T00:00:00Z")));
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B12345674', 'Company Fiscal', cast(? as jsonb))
                """, fixture.companyId(), address());
        jdbc.update("""
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '001', 'Store Fiscal', cast(? as jsonb),
                    'fiscal-hash', 'Atlantic/Canary', 'EUR', 'es-ES')
                """, fixture.storeId(), fixture.companyId(), address());
        jdbc.update("""
                insert into licencia (
                    id, tienda_id, instalacion_id, referencia, valida_desde,
                    valida_hasta, max_windows, max_pda, tax_id, taxpayer_type,
                    regimen_impuesto, blob_original, hash, format_version,
                    importada_en, import_result, activa)
                values (?, ?, ?, 'LIC-FISCAL', ?, ?, 1, 0, 'B12345674',
                    'SOCIEDAD', 'IGIC', 'blob', 'hash', 3, ?, 'ACEPTADA', true)
                """,
                UUID.randomUUID(), fixture.storeId(), fixture.installationId(),
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("""
                insert into configuracion_verifactu (
                    id, empresa_id, activacion_voluntaria, activada_en)
                values (?, ?, true, ?)
                """, UUID.randomUUID(), fixture.companyId(),
                java.sql.Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")));
        jdbc.update("""
                insert into rol (id, tienda_id, nombre, protegido)
                values (?, ?, 'ADMIN', true)
                """, fixture.roleId(), fixture.storeId());
        jdbc.update("""
                insert into usuario (
                    id, tienda_id, nombre, password_hash, rol_id, protegido)
                values (?, ?, 'ADMIN', 'hash', ?, true)
                """, fixture.userId(), fixture.storeId(), fixture.roleId());
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """, fixture.warehouseId(), fixture.storeId());
        for (var index = 1; index <= documentCount; index++) {
            var documentId = UUID.randomUUID();
            var number = "001-260614-%06d".formatted(index);
            fixture.documentIds().add(documentId);
            fixture.numbers().add(number);
            jdbc.update("""
                    insert into documento (
                        id, tienda_id, almacen_id, tipo, estado, numero, fecha,
                        creado_en, confirmado_en, creado_por, confirmado_por,
                        descuento_global, base_total, impuesto_total, total,
                        moneda, origen_stock)
                    values (?, ?, ?, 'TICKET', 'CONFIRMADO', ?, ?, ?, ?, ?, ?,
                        0, 10.00, 2.10, 12.10, 'EUR', true)
                    """,
                    documentId,
                    fixture.storeId(),
                    fixture.warehouseId(),
                    number,
                    LocalDate.of(2026, 6, 14),
                    java.sql.Timestamp.from(NOW.minusSeconds(60)),
                    java.sql.Timestamp.from(NOW.minusSeconds(30)),
                    fixture.userId(),
                    fixture.userId());
        }
        return fixture;
    }

    private static FiscalRecordCommand command(
            Fixture fixture, UUID documentId, Map<String, Object> snapshot) {
        return command(
                fixture, documentId, FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2);
    }

    private static FiscalRecordCommand command(
            Fixture fixture,
            UUID documentId,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType) {
        return new FiscalRecordCommand(
                fixture.companyId(),
                fixture.installationId(),
                fixture.storeId(),
                documentId,
                operation,
                documentType,
                "1.0",
                "SHA-256",
                "0.0.1");
    }

    private <T> T inNewTransaction(java.util.function.Supplier<T> action) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(ignored -> action.get());
    }

    private void assertFiscalMutationRejected(String sql, UUID id) {
        assertThatThrownBy(() -> jdbc.update(sql, id))
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("P0001"));
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " no configurada");
        }
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL", exception);
        }
    }

    private static String address() {
        return """
                {
                    "linea1":"Calle Fiscal",
                    "ciudad":"Las Palmas",
                    "codigoPostal":"35001",
                    "provincia":"Las Palmas",
                    "pais":"ES"
                }
                """;
    }

    @TestConfiguration
    static class Configuration {

        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        VerifactuActivationService activationService() {
            return new VerifactuActivationService();
        }

        @Bean
        FiscalSnapshotFactory snapshotFactory() {
            return new FiscalSnapshotFactory();
        }

        @Bean
        FiscalDocumentPolicy documentPolicy() {
            return new FiscalDocumentPolicy();
        }
    }

    private record Fixture(
            UUID installationId,
            UUID companyId,
            UUID storeId,
            UUID roleId,
            UUID userId,
            UUID warehouseId,
            List<UUID> documentIds,
            List<String> numbers) {
    }
}

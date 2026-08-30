package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.TpvErpBackendApplication;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.loyalty.sync.MemberReturnBalanceRecoveryOutboxPublisher;
import com.tpverp.backend.party.loyalty.sync.MemberReturnBalanceRecoveryRepairService;
import com.tpverp.backend.party.loyalty.sync.MemberReturnBalanceRecoveryRequest;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.shared.api.JacksonConfiguration;
import com.tpverp.backend.sync.SyncOutboxIncidentService;
import com.tpverp.backend.sync.SyncOutboxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class,
        JacksonConfiguration.class,
        MemberReturnBalanceRecoveryRepairService.class,
        MemberReturnBalanceRecoveryOutboxPublisher.class,
        SyncOutboxService.class,
        MemberReturnBalanceRecoveryReplayPostgreSqlTest.FixedClockConfiguration.class})
@ContextConfiguration(classes = TpvErpBackendApplication.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
class MemberReturnBalanceRecoveryReplayPostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "member_recovery_"
            + UUID.randomUUID().toString().replace("-", "");
    private static boolean schemaCreated;

    static {
        if (present(URL) && present(USER) && present(PASSWORD)) {
            LocalMemberBalanceReservationTestDatabaseGuard.validateBeforeSchemaCreation(
                    URL, USER, PASSWORD);
            execute("create schema " + SCHEMA);
            schemaCreated = true;
        }
    }

    @MockitoSpyBean private CommercialDocumentRepository documents;
    @Autowired private MemberReturnBalanceRecoveryRepairService service;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService audit;
    @MockitoBean private SyncOutboxIncidentService incidents;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        if (schemaCreated) {
            execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void concurrentReplayEnqueuesExactlyOneEventAndSecondIsNoOp() throws Exception {
        var fixture = insertFixture();
        var company = mock(Company.class);
        var store = mock(Store.class);
        when(company.getId()).thenReturn(fixture.companyId());
        when(store.getId()).thenReturn(fixture.storeId());
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);

        var firstLocked = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var invocation = new AtomicInteger();
        doAnswer(call -> {
            int number = invocation.incrementAndGet();
            if (number == 2) {
                secondEntered.countDown();
            }
            var result = entityManager.createQuery("""
                    select document
                      from CommercialDocument document
                     where document.returnRequestId = :returnRequestId
                       and document.tiendaId = :storeId
                    """, com.tpverp.backend.document.CommercialDocument.class)
                    .setParameter("returnRequestId", fixture.returnRequestId())
                    .setParameter("storeId", fixture.storeId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
            if (number == 1) {
                firstLocked.countDown();
                assertThat(releaseFirst.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(documents).findLockedByReturnRequestIdAndTiendaId(
                fixture.returnRequestId(), fixture.storeId());

        var request = new MemberReturnBalanceRecoveryRequest(
                fixture.reversalMovementId(), new BigDecimal("0.22"), fixture.fingerprint(), "gate");
        var before = snapshot(fixture);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() ->
                    service.replay(fixture.returnRequestId(), request, "d2b-recovery"));
            if (!firstLocked.await(15, TimeUnit.SECONDS)) {
                // Do not mask an exception raised before the pessimistic-lock barrier.
                // Future#get deliberately propagates the original failure (or a short
                // timeout if the repository call is still blocked) for an actionable
                // integration-test diagnosis.
                first.get(1, TimeUnit.SECONDS);
                fail("El primer replay no alcanzo el bloqueo pesimista");
            }
            assertDocumentRowLocked(fixture.returnDocumentId());

            var second = pool.submit(() ->
                    service.replay(fixture.returnRequestId(), request, "d2b-recovery"));
            assertThat(secondEntered.await(15, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();

            var firstResponse = first.get(20, TimeUnit.SECONDS);
            var secondResponse = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResponse.action()).isEqualTo("ENQUEUE");
            assertThat(secondResponse.action()).isEqualTo("NO_OP");
            assertThat(secondResponse.eventId()).isEqualTo(firstResponse.eventId());
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("""
                select count(*) from sync_outbox
                 where empresa_id = ? and tienda_id = ?
                   and tipo_entidad = ? and entidad_id = ?
                """, Integer.class, fixture.companyId(), fixture.storeId(),
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE,
                fixture.returnRequestId())).isEqualTo(1);
        var after = snapshot(fixture);
        assertThat(after).isEqualTo(before);
    }

    private void assertDocumentRowLocked(UUID returnDocumentId) throws SQLException {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                try (var schema = connection.createStatement()) {
                    schema.execute("set search_path to " + SCHEMA + ", public");
                }
                try (var statement = connection.prepareStatement(
                        "select id from documento where id = ? for update nowait")) {
                    statement.setObject(1, returnDocumentId);
                    statement.executeQuery();
                    fail("La fila de documento no estaba bloqueada por el primer replay");
                } catch (SQLException error) {
                    assertThat(error.getSQLState()).isEqualTo("55P03");
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private Snapshot snapshot(Fixture fixture) {
        return new Snapshot(
                jdbc.queryForObject("select member_balance from miembro where id = ?",
                        BigDecimal.class, fixture.memberId()),
                jdbc.queryForObject("select member_points from miembro where id = ?",
                        Long.class, fixture.memberId()),
                jdbc.queryForObject("select official_member_balance from miembro where id = ?",
                        BigDecimal.class, fixture.memberId()),
                jdbc.queryForObject("select official_member_points from miembro where id = ?",
                        Long.class, fixture.memberId()),
                jdbc.queryForObject("select return_credit_balance from miembro where id = ?",
                        BigDecimal.class, fixture.memberId()),
                jdbc.queryForObject("select official_return_credit_balance from miembro where id = ?",
                        BigDecimal.class, fixture.memberId()),
                jdbc.queryForObject("select count(*) from member_movement where miembro_id = ?",
                        Integer.class, fixture.memberId()),
                jdbc.queryForObject("select coalesce(sum(balance_amount), 0) from member_movement where miembro_id = ?",
                        BigDecimal.class, fixture.memberId()),
                jdbc.queryForObject("select amount_remaining from member_balance_lot where id = ?",
                        BigDecimal.class, fixture.lotId()),
                jdbc.queryForObject("select amount from member_balance_lot_consumption where movement_id = ? and lot_id = ?",
                        BigDecimal.class, fixture.reversalMovementId(), fixture.lotId()));
    }

    private Fixture insertFixture() {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var memberId = UUID.randomUUID();
        var sourceDocumentId = UUID.randomUUID();
        var returnDocumentId = UUID.randomUUID();
        var returnRequestId = UUID.randomUUID();
        var sourceMovementId = UUID.randomUUID();
        var reversalMovementId = UUID.randomUUID();
        var lotId = UUID.randomUUID();
        var now = Instant.parse("2026-08-30T10:00:00Z");

        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B" + companyId.toString().replace("-", "").substring(0, 8),
                "D2B Recovery", address());
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                storeId, companyId, "D2B Store", address(), "d2b-" + storeId,
                "Atlantic/Canary", "EUR", "es-ES", "101");
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado,activo) values (?,?,?,true,true)",
                warehouseId, storeId, "D2B Warehouse");
        jdbc.update("insert into rol(id,tienda_id,nombre,protegido) values (?,?,?,true)",
                roleId, storeId, "ADMIN");
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id,protegido,activo) values (?,?,?, ?,?, ?,true,true)",
                userId, storeId, "ADMIN", "ADMIN", "hash-d2b", roleId);
        jdbc.update("insert into cliente(id,empresa_id,client_id,client_code_store_id,nombre_fiscal,tipo_documento,numero_documento,tarifa,descuento,activo) values (?,?,?, ?,?,'NIF',?,'VENTA',0,true)",
                customerId, companyId, "C-101-000001", storeId, "D2B Customer",
                "12345678Z");
        jdbc.update("insert into miembro(id,empresa_id,cliente_id,member_id,member_code_store_id,num_member,member_since,member_balance,member_points,official_member_balance,official_member_points,return_credit_balance,official_return_credit_balance,active) values (?,?,?,?,?,?,?,0.78,0,0,0,0,0,true)",
                memberId, companyId, customerId, "M-101-000001", storeId, "D2B000001", LocalDate.of(2026, 8, 1));
        insertDocument(sourceDocumentId, storeId, warehouseId, userId, customerId,
                "TICKET", "001-260830-00001", null, LocalDate.of(2026, 8, 30), now,
                new BigDecimal("1.00"));
        insertDocument(returnDocumentId, storeId, warehouseId, userId, customerId,
                "RECTIFICATIVA_VENTA", "001-260830-00003", returnRequestId,
                LocalDate.of(2026, 8, 30), now, new BigDecimal("-0.22"));
        jdbc.update("insert into member_movement(id,empresa_id,tienda_id,miembro_id,documento_id,type,balance_amount,points_amount,reason,created_by_user_id,created_at) values (?,?,?,?,?,'ACUMULACION_SALDO',?,0,?,?,?)",
                sourceMovementId, companyId, storeId, memberId, sourceDocumentId,
                new BigDecimal("1.00"), "acumulacion", userId, Timestamp.from(now));
        jdbc.update("insert into member_movement(id,empresa_id,tienda_id,miembro_id,documento_id,type,balance_amount,points_amount,reason,created_by_user_id,created_at) values (?,?,?,?,?,'DEVOLUCION_ACUMULACION_SALDO',?,0,?,?,?)",
                reversalMovementId, companyId, storeId, memberId, returnDocumentId,
                new BigDecimal("-0.22"), "devolucion", userId, Timestamp.from(now.plusSeconds(1)));
        jdbc.update("insert into member_balance_lot(id,miembro_id,documento_id,balance_type,source_movement_id,amount_original,amount_remaining,created_at) values (?,?,?,'LOYALTY',?,?,?,?)",
                lotId, memberId, sourceDocumentId, sourceMovementId,
                new BigDecimal("1.00"), new BigDecimal("0.78"), Timestamp.from(now));
        jdbc.update("insert into member_balance_lot_consumption(movement_id,lot_id,amount) values (?,?,?)",
                reversalMovementId, lotId, new BigDecimal("0.22"));

        var claim = new MemberBalanceCentralGateway.RetentionClaim(
                lotId, sourceMovementId, sourceDocumentId, new BigDecimal("1.00"),
                new BigDecimal("0.22"));
        var fingerprint = MemberReturnBalanceRetentionPlanner.fingerprint(
                sourceDocumentId, new BigDecimal("0.22"), List.of(claim));
        return new Fixture(companyId, storeId, memberId, sourceDocumentId, returnDocumentId,
                returnRequestId, sourceMovementId, reversalMovementId, lotId, fingerprint);
    }

    private void insertDocument(UUID id, UUID storeId, UUID warehouseId, UUID userId,
            UUID customerId, String type, String number, UUID returnRequestId,
            LocalDate date, Instant now, BigDecimal total) {
        jdbc.update("""
                insert into documento(id,tienda_id,almacen_id,tipo,estado,numero,fecha,creado_en,
                    confirmado_en,creado_por,confirmado_por,cliente_id,return_request_id,
                    descuento_global,base_total,impuesto_total,total,moneda,origen_stock,
                    cuenta_cobrar,wholesale_mode,liquidado_por_origen)
                values (?,?,?, ?, 'CONFIRMADO', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, 'EUR', false, false, false, false)
                """, id, storeId, warehouseId, type, number, date, Timestamp.from(now), Timestamp.from(now),
                userId, userId, customerId, returnRequestId, total, total);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception error) {
            throw new IllegalStateException("No se pudo preparar el schema PostgreSQL de pruebas", error);
        }
    }

    private static String address() {
        return "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
    }

    private record Fixture(UUID companyId, UUID storeId, UUID memberId,
            UUID sourceDocumentId, UUID returnDocumentId, UUID returnRequestId,
            UUID sourceMovementId, UUID reversalMovementId, UUID lotId, String fingerprint) {
    }

    private record Snapshot(BigDecimal memberBalance, Long memberPoints,
            BigDecimal officialMemberBalance, Long officialMemberPoints,
            BigDecimal returnCreditBalance, BigDecimal officialReturnCreditBalance,
            Integer movementCount, BigDecimal movementBalance, BigDecimal lotRemaining,
            BigDecimal consumption) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}

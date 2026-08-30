package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.TpvErpBackendApplication;
import com.tpverp.backend.party.MemberBalanceLotType;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@ContextConfiguration(classes = TpvErpBackendApplication.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class LocalMemberBalanceReservationJsonbPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "member_retention_"
            + UUID.randomUUID().toString().replace("-", "");
    private static boolean schemaCreated;

    static {
        if (URL != null && USER != null && PASSWORD != null) {
            LocalMemberBalanceReservationTestDatabaseGuard.validateBeforeSchemaCreation(URL, USER, PASSWORD);
            execute("create schema " + SCHEMA);
            schemaCreated = true;
        }
    }

    @Autowired private LocalMemberBalanceReservationRepository reservations;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

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
        if (schemaCreated) execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void persistsFlushesClearsAndReloadsRetentionReservedLotsJsonb() {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B" + companyId.toString().replace("-", "").substring(0, 8),
                "Retention test", address());
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                storeId, companyId, "Retention store", address(), "retention-" + storeId,
                "Atlantic/Canary", "EUR", "es-ES", "101");
        jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values (?,?,?,'TERMINAL_VENTA',?)",
                terminalId, storeId, "Retention terminal", "hash-" + terminalId);
        var retentionClaim = new MemberBalanceCentralGateway.RetentionClaim(
                lotId, movementId, documentId, new BigDecimal(".09"),
                new BigDecimal(".04"), new BigDecimal(".04"));
        var retentionClaims = List.of(retentionClaim);
        var retentionFingerprint = MemberReturnBalanceRetentionPlanner.fingerprint(
                documentId, new BigDecimal(".04"), retentionClaims);
        var response = new MemberBalanceCentralGateway.ReservationResponse(
                UUID.randomUUID(), memberId, "ACTIVE", new BigDecimal(".09"), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), null,
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal(".09"), BigDecimal.ZERO.setScale(2),
                List.of(new MemberBalanceCentralGateway.ReservedLot(
                        MemberBalanceLotType.LOYALTY, lotId, new BigDecimal(".09"), now,
                        null, movementId, documentId)),
                retentionClaims,
                now, now.plusSeconds(120), 30, 120, 1L, retentionFingerprint,
                new BigDecimal(".04"), new BigDecimal(".04"), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), new BigDecimal(".05"), BigDecimal.ZERO.setScale(2));
        var entity = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-" + UUID.randomUUID(), response, now);

        reservations.saveAndFlush(entity);
        var id = entity.getId();
        reservations.flush();
        entityManager.clear();
        var reloaded = reservations.findById(id).orElseThrow();

        assertThat(reloaded.getAccountBalance()).isEqualByComparingTo("0.09");
        assertThat(jdbc.queryForObject(
                "select account_balance from member_balance_reservation_local where id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("0.09");
        assertThat(jdbc.queryForObject(
                "select jsonb_typeof(retention_reserved_lots) from member_balance_reservation_local where id = ?",
                String.class, id)).isEqualTo("array");
        assertThat(jdbc.queryForObject(
                "select retention_reserved_lots->0->>'heldAmount' from member_balance_reservation_local where id = ?",
                String.class, id)).isEqualTo("0.04");
        assertThat(jdbc.queryForObject(
                "select retention_attributed_amount from member_balance_reservation_local where id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("0.04");
        assertThat(jdbc.queryForObject(
                "select retention_held_known from member_balance_reservation_local where id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("0.04");
        assertThat(jdbc.queryForObject(
                "select retention_spendable from member_balance_reservation_local where id = ?",
                BigDecimal.class, id)).isEqualByComparingTo("0.05");

        assertThat(reloaded.getRetentionAttributedAmount()).isEqualByComparingTo("0.04");
        assertThat(reloaded.getRetentionHeldKnown()).isEqualByComparingTo("0.04");
        assertThat(reloaded.getRetentionSpendable()).isEqualByComparingTo("0.05");
        assertThat(reloaded.getRetentionFingerprint()).isEqualTo(retentionFingerprint);
        assertThat(reloaded.getRetentionReservedLots()).containsExactly(
                new LocalMemberBalanceReservation.RetentionReservedLotSnapshot(
                        lotId, "LOYALTY", new BigDecimal(".09"), new BigDecimal(".04"),
                        movementId, documentId));
        assertThat(reloaded.getRetentionReservedLots().getFirst().heldAmount())
                .isEqualByComparingTo("0.04");

        // V1/V2 rows did not have heldAmount. The JSONB converter must keep
        // those snapshots readable and fail closed at zero hold.
        jdbc.update("update member_balance_reservation_local set retention_reserved_lots = cast(? as jsonb) where id = ?",
                "[{\"lotId\":\"" + lotId + "\",\"balanceType\":\"LOYALTY\","
                        + "\"remainingAmount\":0.09,\"sourceMovementId\":\"" + movementId
                        + "\",\"documentId\":\"" + documentId + "\"}]", id);
        entityManager.clear();
        var legacy = reservations.findById(id).orElseThrow();
        assertThat(legacy.getRetentionReservedLots().getFirst().heldAmount())
                .isEqualByComparingTo("0.00");
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String address() {
        return "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
    }
}

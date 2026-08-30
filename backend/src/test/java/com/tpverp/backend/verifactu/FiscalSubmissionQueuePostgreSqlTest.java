package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class FiscalSubmissionQueuePostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "fiscal_claim_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private FiscalSubmissionStateRepository states;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + "?currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table instalacion, empresa cascade");
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void nativeClaimKeepsChainOrderAndReclaimsExpiredLease() {
        var fixture = fixture();
        insertRecord(fixture, 1, "A".repeat(64), null);
        insertRecord(fixture, 2, "B".repeat(64), "A".repeat(64));
        insertState(fixture.recordOne(), "ACEPTADO", null, null, null);
        insertState(fixture.recordTwo(), "ENVIANDO", null, NOW.minusSeconds(1), UUID.randomUUID());

        var claimed = states.findClaimable(NOW, 10);

        assertThat(claimed).extracting(FiscalSubmissionState::getRecordId)
                .containsExactly(fixture.recordTwo());
    }

    @Test
    void nativeClaimBlocksWhenPredecessorIsStillInFlight() {
        var fixture = fixture();
        insertRecord(fixture, 1, "A".repeat(64), null);
        insertRecord(fixture, 2, "B".repeat(64), "A".repeat(64));
        insertState(fixture.recordOne(), "ENVIANDO", null, NOW.plusSeconds(120), UUID.randomUUID());
        insertState(fixture.recordTwo(), "PENDIENTE", NOW, null, null);

        assertThat(states.findClaimable(NOW, 10)).isEmpty();
    }

    @Test
    void nativeClaimAllowsCorrectionAfterAeAtRejection() {
        var fixture = fixture();
        insertRecord(fixture, 1, "A".repeat(64), null);
        insertRecord(fixture, 2, "B".repeat(64), "A".repeat(64));
        insertState(fixture.recordOne(), "RECHAZADO", null, null, null);
        insertState(fixture.recordTwo(), "PENDIENTE", NOW, null, null);

        assertThat(states.findClaimable(NOW, 10)).extracting(FiscalSubmissionState::getRecordId)
                .containsExactly(fixture.recordTwo());
    }

    @Test
    void nativeClaimIgnoresNoVerifactuPredecessorInTheSameChain() {
        var fixture = fixture();
        insertRecord(fixture, 1, "A".repeat(64), null);
        insertRecord(fixture, 2, "B".repeat(64), "A".repeat(64));
        jdbc.update("update registro_fiscal set modo_fiscal = 'NO_VERIFACTU' where id = ?",
                fixture.recordOne());
        insertState(fixture.recordOne(), "PENDIENTE", NOW, null, null);
        insertState(fixture.recordTwo(), "PENDIENTE", NOW, null, null);

        assertThat(states.findClaimable(NOW, 10)).extracting(FiscalSubmissionState::getRecordId)
                .containsExactly(fixture.recordTwo());
    }

    @Test
    void nativeClaimSelectsContiguousDuePrefixAtSequence1000And1001() {
        var fixture = fixture();
        insertRecord(fixture, 1000, "A".repeat(64), null);
        insertRecord(fixture, 1001, "B".repeat(64), "A".repeat(64));
        insertState(fixture.recordOne(), "PENDIENTE", NOW, null, null);
        insertState(fixture.recordTwo(), "PENDIENTE", NOW, null, null);

        assertThat(states.findClaimable(NOW, 1000)).extracting(FiscalSubmissionState::getRecordId)
                .containsExactly(fixture.recordOne(), fixture.recordTwo());
    }

    private Fixture fixture() {
        var installation = UUID.randomUUID();
        var company = UUID.randomUUID();
        var store = UUID.randomUUID();
        var chain = UUID.randomUUID();
        jdbc.update("insert into instalacion (id, referencia, public_key, creada_en, demo_hasta) values (?, 'CLAIM-TEST', 'key', ?, ?)",
                installation, NOW, NOW.plusSeconds(30L * 24 * 3600));
        jdbc.update("insert into empresa (id, tax_id, razon_social, domicilio_fiscal) values (?, 'B12345674', 'Claim test', cast(? as jsonb))",
                company, "{\"linea1\":\"Calle\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}");
        jdbc.update("insert into tienda (id, empresa_id, nombre, direccion, address_normalized_hash, timezone, moneda, locale) values (?, ?, 'Claim', cast(? as jsonb), 'claim-hash', 'Atlantic/Canary', 'EUR', 'es-ES')",
                store, company, "{\"linea1\":\"Calle\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}");
        jdbc.update("insert into cadena_fiscal (id, empresa_id, instalacion_id, ultima_secuencia, actualizada_en) values (?, ?, ?, 0, ?)", chain, company, installation, NOW);
        return new Fixture(installation, company, store, chain, UUID.randomUUID(), UUID.randomUUID());
    }

    private void insertRecord(Fixture f, long sequence, String hash, String previousHash) {
        UUID id = sequence == 1 ? f.recordOne() : f.recordTwo();
        jdbc.update("""
                insert into registro_fiscal (id, cadena_id, empresa_id, instalacion_id, tienda_id,
                    secuencia, operacion, tipo_documento_fiscal, serie_numero, fecha_expedicion,
                    generado_en, zona_horaria, nif_emisor, cuota_total, importe_total,
                    huella_anterior, huella, hash_snapshot, snapshot, version_formato,
                    version_algoritmo, version_aplicacion, modo_fiscal)
                values (?, ?, ?, ?, ?, ?, 'ALTA', 'F1', ?, ?, ?, 'Atlantic/Canary',
                    'B12345674', 2.10, 12.10, ?, ?, ?, cast(? as jsonb), '1', 'SHA-256', '1', 'VERIFACTU')
                """, id, f.chain(), f.company(), f.installation(), f.store(), sequence,
                "F-" + sequence, NOW, NOW, previousHash, hash, "C".repeat(64), "{}");
    }

    private void insertState(UUID record, String status, Instant nextAttempt, Instant leaseUntil, UUID token) {
        jdbc.update("insert into estado_envio_fiscal (registro_id, estado, actualizado_en, proximo_intento_en, lease_hasta, lease_owner, claim_token) values (?, ?, ?, ?, ?, ?, ?)",
                record, status, NOW, nextAttempt, leaseUntil, token == null ? null : UUID.randomUUID(), token);
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL", exception);
        }
    }

    private record Fixture(UUID installation, UUID company, UUID store, UUID chain,
                           UUID recordOne, UUID recordTwo) {
    }
}

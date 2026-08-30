package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-only proof of the DB append-only and uniqueness guarantees. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FiscalSubmissionEvidencePostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "fiscal_evidence_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    static {
        execute("create schema " + SCHEMA);
    }

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
    void bloqueaUpdateYDeleteDeEvidencia() {
        var fixture = fixture();
        var evidence = UUID.randomUUID();
        insertEvidence(fixture, evidence);

        assertThatThrownBy(() -> jdbc.update(
                "update evidencia_envio_fiscal set request_sha256 = ? where id = ?",
                "B".repeat(64), evidence)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                "delete from evidencia_envio_fiscal where id = ?", evidence))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void dosBatchIdBajoElMismoOwnerSoloPermitenUnaEvidencia() throws Exception {
        var fixture = fixture();
        var firstBatch = UUID.randomUUID();
        var secondBatch = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = java.util.List.<Callable<Boolean>>of(
                    () -> insertEvidenceIfAvailable(fixture, firstBatch),
                    () -> insertEvidenceIfAvailable(fixture, secondBatch));
            var results = pool.invokeAll(tasks);
            assertThat(results.stream().filter(value -> {
                try { return value.get(); } catch (Exception exception) { return false; }
            }).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Fixture fixture() {
        var installation = UUID.randomUUID();
        var company = UUID.randomUUID();
        var owner = UUID.randomUUID();
        jdbc.update("insert into instalacion (id, referencia, public_key, creada_en, demo_hasta) values (?, 'EVIDENCE-TEST', 'key', ?, ?)",
                installation, timestamp(NOW), timestamp(NOW.plusSeconds(30L * 24 * 3600)));
        jdbc.update("insert into empresa (id, tax_id, razon_social, domicilio_fiscal) values (?, 'B12345674', 'Evidence test', cast(? as jsonb))",
                company, "{\"linea1\":\"Calle\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}");
        jdbc.update("insert into flujo_envio_fiscal_scope (id, empresa_id, instalacion_id, entorno) values (?, ?, ?, 'TEST')",
                UUID.randomUUID(), company, installation);
        return new Fixture(company, installation, owner);
    }

    private void insertEvidence(Fixture fixture, UUID id) {
        jdbc.update("""
                insert into evidencia_envio_fiscal
                    (id, empresa_id, instalacion_id, entorno, batch_owner, creado_en,
                     request_preparado_en, request_xml, request_sha256)
                values (?, ?, ?, 'TEST', ?, ?, ?, '<soap/>', ?)
                """, id, fixture.company(), fixture.installation(), fixture.owner(),
                timestamp(NOW), timestamp(NOW),
                FiscalSubmissionEvidence.sha256("<soap/>") );
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private boolean insertEvidenceIfAvailable(Fixture fixture, UUID id) {
        try {
            insertEvidence(fixture, id);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL", exception);
        }
    }

    private record Fixture(UUID company, UUID installation, UUID owner) {
    }
}

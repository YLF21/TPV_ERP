package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MigrationV17ContractTest {

    private static final String SCHEMA =
            "tpv_erp_cash_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path KEY_DIRECTORY = Path.of(
            "target", "test-installation-keys", UUID.randomUUID().toString());
    private static final Path CERTIFICATE_DIRECTORY = Path.of(
            "target", "test-verifactu-certificates", UUID.randomUUID().toString());

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> databaseUrl()
                + (databaseUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username",
                () -> environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"));
        registry.add("spring.datasource.password",
                () -> environment("TPV_TEST_DB_PASSWORD", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("tpv.installation.key-directory", KEY_DIRECTORY::toString);
        registry.add("tpv.verifactu.secret-directory", CERTIFICATE_DIRECTORY::toString);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                databaseUrl(),
                environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"),
                environment("TPV_TEST_DB_PASSWORD", ""));
                var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
        deleteDirectory(KEY_DIRECTORY);
        deleteDirectory(CERTIFICATE_DIRECTORY);
    }

    @Test
    void creaTablasDeCajaYBloqueaDosSesionesAbiertasPorTerminal() {
        assertTableExists("configuracion_caja_tienda");
        assertTableExists("sesion_caja");
        assertTableExists("movimiento_caja");
        assertTableExists("movimiento_caja_denominacion");
        assertTableExists("intento_arqueo_caja");

        UUID storeId = jdbcTemplate.queryForObject("select id from tienda limit 1", UUID.class);
        UUID terminalId = jdbcTemplate.queryForObject("select id from terminal limit 1", UUID.class);
        UUID userId = jdbcTemplate.queryForObject("select id from usuario limit 1", UUID.class);

        insertOpenSession(UUID.randomUUID(), storeId, terminalId, userId);

        assertThatThrownBy(() -> insertOpenSession(
                UUID.randomUUID(), storeId, terminalId, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sesion_caja_terminal_abierta_uq");
    }

    @Test
    void rechazaSesionConTerminalDeOtraTienda() {
        UUID terminalId = jdbcTemplate.queryForObject("select id from terminal limit 1", UUID.class);
        UUID userId = jdbcTemplate.queryForObject("select id from usuario limit 1", UUID.class);
        UUID otherStoreId = insertOtherStore();

        assertThatThrownBy(() -> insertOpenSession(
                UUID.randomUUID(), otherStoreId, terminalId, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sesion_caja_terminal_tienda_fk");
    }

    @Test
    void rechazaMovimientoConTerminalDeOtraTienda() {
        UUID terminalId = jdbcTemplate.queryForObject("select id from terminal limit 1", UUID.class);
        UUID userId = jdbcTemplate.queryForObject("select id from usuario limit 1", UUID.class);
        UUID otherStoreId = insertOtherStore();

        assertThatThrownBy(() -> insertCashMovement(
                UUID.randomUUID(), otherStoreId, terminalId, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("movimiento_caja_terminal_tienda_fk");
    }

    @Test
    void rechazaMovimientoConSesionDeOtraTerminalYTienda() {
        UUID storeId = jdbcTemplate.queryForObject("select id from tienda limit 1", UUID.class);
        UUID terminalId = jdbcTemplate.queryForObject("select id from terminal limit 1", UUID.class);
        UUID userId = jdbcTemplate.queryForObject("select id from usuario limit 1", UUID.class);
        UUID sessionId = UUID.randomUUID();
        insertOpenSession(sessionId, storeId, terminalId, userId);

        UUID otherStoreId = insertOtherStore();
        UUID otherTerminalId = insertTerminal(otherStoreId);

        assertThatThrownBy(() -> insertCashMovement(
                UUID.randomUUID(), otherStoreId, otherTerminalId, sessionId, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("movimiento_caja_sesion_terminal_tienda_fk");
    }

    private void assertTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = current_schema()
                  and table_name = ?
                """, Integer.class, tableName);
        assertThat(count).as("Debe existir la tabla %s", tableName).isOne();
    }

    private void insertOpenSession(
            UUID id, UUID storeId, UUID terminalId, UUID userId) {
        jdbcTemplate.update("""
                insert into sesion_caja (
                    id, tienda_id, terminal_id, usuario_apertura_id,
                    abierta_en, fondo_inicial, estado)
                values (?, ?, ?, ?, now(), 10.00, 'ABIERTA')
                """, id, storeId, terminalId, userId);
    }

    private void insertCashMovement(
            UUID id, UUID storeId, UUID terminalId, UUID userId) {
        jdbcTemplate.update("""
                insert into movimiento_caja (
                    id, tienda_id, terminal_id, tipo, importe, creado_en, usuario_id)
                values (?, ?, ?, 'ENTRADA', 5.00, now(), ?)
                """, id, storeId, terminalId, userId);
    }

    private void insertCashMovement(
            UUID id, UUID storeId, UUID terminalId, UUID sessionId, UUID userId) {
        jdbcTemplate.update("""
                insert into movimiento_caja (
                    id, tienda_id, terminal_id, sesion_caja_id, tipo,
                    importe, creado_en, usuario_id)
                values (?, ?, ?, ?, 'ENTRADA', 5.00, now(), ?)
                """, id, storeId, terminalId, sessionId, userId);
    }

    private UUID insertOtherStore() {
        UUID storeId = UUID.randomUUID();
        UUID companyId = jdbcTemplate.queryForObject("select id from empresa limit 1", UUID.class);
        jdbcTemplate.update("""
                insert into tienda (
                    id, empresa_id, nombre, direccion, address_normalized_hash,
                    timezone, moneda, locale, codigo_tienda)
                values (
                    ?, ?, 'OTRA TIENDA', '{
                        "linea1":"Calle Dos",
                        "ciudad":"Las Palmas",
                        "codigoPostal":"35002",
                        "provincia":"Las Palmas",
                        "pais":"ES"
                    }', ?, 'Atlantic/Canary', 'EUR', 'es-ES', '002')
                """, storeId, companyId, "cash-test-" + storeId);
        return storeId;
    }

    private UUID insertTerminal(UUID storeId) {
        UUID terminalId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into terminal (
                    id, tienda_id, nombre, tipo, credential_hash)
                values (?, ?, 'CAJA SECUNDARIA', 'TERMINAL_VENTA', 'hash')
                """, terminalId, storeId);
        return terminalId;
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String databaseUrl() {
        return environment("TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

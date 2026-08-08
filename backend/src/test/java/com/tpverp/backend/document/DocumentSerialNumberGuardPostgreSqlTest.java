package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class DocumentSerialNumberGuardPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "serial_guard_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CommercialDocumentRepository documents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ticketAndIndependentDeliveryNoteCannotClaimSameSerialConcurrently()
            throws Exception {
        var fixture = insertFixture();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ticket = pool.submit(() -> claim(
                    fixture, CommercialDocumentType.TICKET, "T-1", start));
            var deliveryNote = pool.submit(() -> claim(
                    fixture, CommercialDocumentType.ALBARAN_VENTA, "AV-1", start));
            start.countDown();

            assertThat(List.of(
                    ticket.get(15, TimeUnit.SECONDS),
                    deliveryNote.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from documento_linea_numero_serie serial
                  join documento_linea line on line.id = serial.documento_linea_id
                  join documento document on document.id = line.documento_id
                 where document.tienda_id = ?
                   and upper(trim(serial.numero_serie)) = 'SN-SHARED'
                """, Integer.class, fixture.storeId())).isEqualTo(1);
    }

    private boolean claim(
            Fixture fixture,
            CommercialDocumentType type,
            String number,
            CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(ignored -> {
                var candidate = candidate(fixture, type);
                new DocumentSerialNumberGuard(documents).lockAndValidate(candidate, true);
                insertConfirmedClaim(fixture, type, number);
                try {
                    Thread.sleep(150);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            return true;
        } catch (IllegalArgumentException exception) {
            assertThat(exception).hasMessage(
                    "message.document.serial_number_already_used");
            return false;
        }
    }

    private CommercialDocument candidate(Fixture fixture, CommercialDocumentType type) {
        var document = new CommercialDocument(
                fixture.storeId(), fixture.warehouseId(), type,
                LocalDate.of(2026, 8, 7), fixture.userId(), BigDecimal.ZERO);
        var line = new DocumentLine(
                document, fixture.productId(), 1, BigDecimal.ONE,
                "P-1", "Producto", "VENTA", BigDecimal.TEN, BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21"));
        line.assignSerialNumbers(List.of("sn-shared"));
        document.addLine(line);
        return document;
    }

    private void insertConfirmedClaim(
            Fixture fixture, CommercialDocumentType type, String number) {
        var documentId = UUID.randomUUID();
        var lineId = UUID.randomUUID();
        jdbc.update("""
                insert into documento (
                    id, tienda_id, almacen_id, tipo, estado, numero, fecha,
                    creado_en, confirmado_en, creado_por, confirmado_por,
                    descuento_global, base_total, impuesto_total, total,
                    moneda, origen_stock)
                values (?, ?, ?, ?, 'CONFIRMADO', ?, current_date, now(), now(),
                    ?, ?, 0, 8.26, 1.74, 10.00, 'EUR', true)
                """, documentId, fixture.storeId(), fixture.warehouseId(),
                type.name(), number, fixture.userId(), fixture.userId());
        jdbc.update("""
                insert into documento_linea (
                    id, documento_id, producto_id, tipo_linea, posicion, cantidad,
                    codigo, nombre, tarifa, precio_unitario, descuento,
                    impuestos_incluidos, regimen_impuesto, porcentaje_impuesto,
                    base, impuesto, total)
                values (?, ?, ?, 'PRODUCT', 1, 1, 'P-1', 'Producto', 'VENTA',
                    10.00, 0, true, 'IVA', 21, 8.26, 1.74, 10.00)
                """, lineId, documentId, fixture.productId());
        jdbc.update("""
                insert into documento_linea_numero_serie (
                    documento_linea_id, posicion, numero_serie)
                values (?, 0, 'SN-SHARED')
                """, lineId);
    }

    private Fixture insertFixture() {
        var fixture = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B00000000', 'Company', cast(? as jsonb))
                """, fixture.companyId(), address());
        jdbc.update("""
                insert into tienda (
                    id, empresa_id, codigo_tienda, nombre, direccion,
                    address_normalized_hash, timezone, moneda, locale)
                values (?, ?, '001', 'Store', cast(? as jsonb), 'hash',
                    'Atlantic/Canary', 'EUR', 'es-ES')
                """, fixture.storeId(), fixture.companyId(), address());
        jdbc.update("""
                insert into rol (id, tienda_id, nombre, protegido)
                values (?, ?, 'ADMIN', true)
                """, fixture.roleId(), fixture.storeId());
        jdbc.update("""
                insert into usuario (
                    id, tienda_id, nombre, user_name, password_hash, rol_id, protegido)
                values (?, ?, 'ADMIN', 'ADMIN', 'hash', ?, true)
                """, fixture.userId(), fixture.storeId(), fixture.roleId());
        jdbc.update("""
                insert into impuesto_tienda (id, tienda_id, porcentaje)
                values (?, ?, 21)
                """, fixture.taxId(), fixture.storeId());
        jdbc.update("""
                insert into familia (id, tienda_id, nombre)
                values (?, ?, 'GENERAL')
                """, fixture.familyId(), fixture.storeId());
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado)
                values (?, ?, 'GENERAL', true)
                """, fixture.warehouseId(), fixture.storeId());
        jdbc.update("""
                insert into producto (id, tienda_id, familia_id, impuesto_id, nombre)
                values (?, ?, ?, ?, 'Producto')
                """, fixture.productId(), fixture.storeId(), fixture.familyId(),
                fixture.taxId());
        return fixture;
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
                    "linea1":"Calle Uno",
                    "ciudad":"Las Palmas",
                    "codigoPostal":"35001",
                    "provincia":"Las Palmas",
                    "pais":"ES"
                }
                """;
    }

    private record Fixture(
            UUID companyId,
            UUID storeId,
            UUID roleId,
            UUID userId,
            UUID taxId,
            UUID familyId,
            UUID warehouseId,
            UUID productId) {
    }
}

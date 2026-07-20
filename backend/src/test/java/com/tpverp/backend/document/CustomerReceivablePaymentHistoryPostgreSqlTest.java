package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class CustomerReceivablePaymentHistoryPostgreSqlTest {

    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "receivable_history_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant END = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final UUID EMPTY_ID = new UUID(0L, 0L);

    static {
        execute("create schema " + SCHEMA);
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void cleanup() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Autowired private DocumentPaymentRepository payments;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void emptyOptionalFiltersAreTypedAndExecutableOnPostgreSql() {
        var fixture = insertFixture();

        var result = payments.findCustomerReceivablePaymentHistory(
                fixture.storeId(), Instant.EPOCH, END,
                false, EMPTY_ID, false, EMPTY_ID);

        assertThat(result).extracting(DocumentPayment::getId)
                .containsExactly(fixture.paymentId());
    }

    @Test
    void customerAndPaymentMethodFiltersRemainDatabaseScoped() {
        var fixture = insertFixture();

        assertThat(payments.findCustomerReceivablePaymentHistory(
                fixture.storeId(), Instant.EPOCH, END,
                true, fixture.methodId(), true, fixture.customerId()))
                .extracting(DocumentPayment::getId)
                .containsExactly(fixture.paymentId());
        assertThat(payments.findCustomerReceivablePaymentHistory(
                fixture.storeId(), Instant.EPOCH, END,
                true, UUID.randomUUID(), true, fixture.customerId())).isEmpty();
    }

    private Fixture insertFixture() {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var methodId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var paymentId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B00000001", "Test", "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}");
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                storeId, companyId, "T", "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}", "h", "Europe/Madrid", "EUR", "es-ES", "001");
        jdbc.update("insert into rol(id,tienda_id,nombre) values (?,?,?)", roleId, storeId, "SELLER");
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values (?,?,?,?,?,?)",
                userId, storeId, "SELLER", "Seller", "h", roleId);
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado) values (?,?,?,true)",
                warehouseId, storeId, "A");
        jdbc.update("insert into cliente(id,empresa_id,client_id,client_code_store_id,nombre_fiscal,tipo_documento,numero_documento,tarifa,descuento) values (?,?,?,?,?,'CIF',?,'VENTA',0)",
                customerId, companyId, "C-001-000001", storeId, "Cliente", "B00000002");
        jdbc.update("insert into metodo_pago(id,empresa_id,nombre) values (?,?,?)",
                methodId, companyId, "EFECTIVO");
        jdbc.update("insert into documento(id,tienda_id,almacen_id,tipo,estado,numero,fecha,creado_en,confirmado_en,creado_por,confirmado_por,cliente_id,total) values (?,?,?,'FACTURA_VENTA','PAGADO',?,current_date,now(),now(),?,?,?,25)",
                documentId, storeId, warehouseId, "FV-TEST-" + documentId.toString().substring(0, 8),
                userId, userId, customerId);
        jdbc.update("insert into documento_pago(id,documento_id,metodo_pago_id,posicion,importe,principal,creado_en) values (?,?,?,?,25,true,now())",
                paymentId, documentId, methodId, 1);
        return new Fixture(storeId, customerId, methodId, paymentId);
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record Fixture(UUID storeId, UUID customerId, UUID methodId, UUID paymentId) {}
}

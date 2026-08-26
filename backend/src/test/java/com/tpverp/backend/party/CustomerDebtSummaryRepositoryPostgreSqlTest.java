package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class CustomerDebtSummaryRepositoryPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA =
            "customer_debt_summary_" + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private CustomerRepository customers;
    @Autowired private JdbcTemplate jdbc;

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
    void aggregatesOutstandingAndOverdueDebtForTheRequestedCustomers() {
        var fixture = insertFixture();

        List<CustomerRepository.CustomerDebtSummary> summaries = customers.debtSummaries(
                List.of(fixture.customerId()), LocalDate.of(2026, 8, 11));

        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().getCustomerId()).isEqualTo(fixture.customerId());
        assertThat(summaries.getFirst().getOutstandingDebt()).isEqualByComparingTo("90.00");
        assertThat(summaries.getFirst().getOverdueDebt()).isEqualByComparingTo("60.00");
    }

    private Fixture insertFixture() {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var methodId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B00000001", "Test", address());
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                storeId, companyId, "T", address(), "h", "Atlantic/Canary", "EUR", "es-ES", "001");
        jdbc.update("insert into rol(id,tienda_id,nombre) values (?,?,?)", roleId, storeId, "SELLER");
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values (?,?,?,?,?,?)",
                userId, storeId, "SELLER", "Seller", "h", roleId);
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado) values (?,?,?,true)",
                warehouseId, storeId, "A");
        jdbc.update("insert into cliente(id,empresa_id,client_id,client_code_store_id,nombre_fiscal,tipo_documento,numero_documento,tarifa,descuento) values (?,?,?,?,?,'CIF',?,'VENTA',0)",
                customerId, companyId, "C-001-000001", storeId, "Cliente", "B00000002");
        jdbc.update("insert into metodo_pago(id,empresa_id,nombre) values (?,?,?)",
                methodId, companyId, "EFECTIVO");

        var overdueInvoice = document(storeId, warehouseId, userId, customerId,
                "FACTURA_VENTA", "PARCIAL", "FV-OVERDUE", "2026-08-01", "100.00", false);
        jdbc.update("insert into documento_pago(id,documento_id,metodo_pago_id,posicion,importe,principal,creado_en) values (?,?,?,?,?,true,now())",
                UUID.randomUUID(), overdueInvoice, methodId, 1, new BigDecimal("40.00"));
        document(storeId, warehouseId, userId, customerId,
                "TICKET", "PENDIENTE", "T-PENDING", "2026-08-20", "30.00", true);
        document(storeId, warehouseId, userId, customerId,
                "TICKET", "PENDIENTE", "T-NON-RECEIVABLE", "2026-08-01", "50.00", false);
        return new Fixture(customerId);
    }

    private UUID document(
            UUID storeId,
            UUID warehouseId,
            UUID userId,
            UUID customerId,
            String type,
            String status,
            String number,
            String dueDate,
            String total,
            boolean receivable) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into documento(
                    id,tienda_id,almacen_id,tipo,estado,numero,fecha,fecha_vencimiento,
                    creado_en,confirmado_en,creado_por,confirmado_por,cliente_id,total,cuenta_cobrar)
                values (?,?,?,?,?,?,current_date,cast(? as date),now(),now(),?,?,?,?,?)
                """, id, storeId, warehouseId, type, status, number, dueDate,
                userId, userId, customerId, new BigDecimal(total), receivable);
        return id;
    }

    private static String address() {
        return "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record Fixture(UUID customerId) {}
}

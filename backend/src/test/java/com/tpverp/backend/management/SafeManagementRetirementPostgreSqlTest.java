package com.tpverp.backend.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Tag("postgresql")
class SafeManagementRetirementPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "safe_management_"
            + UUID.randomUUID().toString().replace("-", "");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private SafeManagementRetirementService service;
    private UUID companyId;
    private UUID storeId;

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

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        var organization = mock(CurrentOrganization.class);
        var company = mock(Company.class);
        var store = mock(Store.class);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        service = new SafeManagementRetirementService(
                entityManager, jdbc, organization, mock(AuditService.class),
                mock(MemberLoyaltyService.class));
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void readsEveryManagementDirectoryWithTheFrontendContractAndTenantScope() {
        var ids = insertFixture();

        var products = service.page(
                SafeManagementRetirementService.EntityType.PRODUCT, 25, null, null, null);
        var customers = service.page(
                SafeManagementRetirementService.EntityType.CUSTOMER, 25, null, null, null);
        var suppliers = service.page(
                SafeManagementRetirementService.EntityType.SUPPLIER, 25, null, null, null);
        var representatives = service.page(
                SafeManagementRetirementService.EntityType.SALES_REPRESENTATIVE,
                25, null, null, null);

        assertThat(products.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(ids.productId());
            assertThat(item.code()).isEqualTo("P-001");
            assertThat(item.name()).isEqualTo("Producto de prueba");
        });
        assertThat(customers.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(ids.customerId());
            assertThat(item.clientId()).isEqualTo("C-001-000001");
            assertThat(item.fiscalName()).isEqualTo("Cliente de prueba");
            assertThat(item.discount()).isEqualByComparingTo("12.50");
            assertThat(item.birthday()).isEqualTo(java.time.LocalDate.of(1990, 5, 20));
            assertThat(item.gender()).isEqualTo("OTRO");
            assertThat(item.commercialConsent()).isTrue();
            assertThat(item.preferredCommercialChannelId()).isEqualTo(ids.channelId());
            assertThat(item.creditEnabled()).isFalse();
            assertThat(item.creditLimit()).isEqualByComparingTo("150.00");
            assertThat(item.paymentTermDays()).isEqualTo(45);
            assertThat(item.creditBlocked()).isTrue();
            assertThat(item.blockOnOverdue()).isTrue();
        });
        assertThat(suppliers.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(ids.supplierId());
            assertThat(item.supplierId()).isEqualTo("S-000001");
            assertThat(item.legalName()).isEqualTo("Proveedor de prueba");
        });
        assertThat(representatives.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(ids.representativeId());
            assertThat(item.commercialId()).isEqualTo("CO-000001");
            assertThat(item.suppliers()).singleElement().satisfies(link -> {
                assertThat(link.supplierId()).isEqualTo(ids.supplierId());
                assertThat(link.primary()).isTrue();
            });
        });
        assertThat(products.hasMore()).isFalse();
        assertThat(customers.hasMore()).isFalse();
        assertThat(suppliers.hasMore()).isFalse();
        assertThat(representatives.hasMore()).isFalse();
    }

    @Test
    void searchesBusinessColumnsAndKeepsDescendingKeysetOrder() {
        var ids = insertFixture();
        UUID secondCustomerId = UUID.randomUUID();
        jdbc.update("""
                insert into cliente(
                    id,empresa_id,client_id,client_code_store_id,nombre_fiscal,
                    tipo_documento,numero_documento,tarifa,descuento)
                values (?,?,?,?,?,'CIF',?,'VENTA',0)
                """, secondCustomerId, companyId, "C-001-000002", storeId,
                "Abastos del Norte", "B00000004");

        assertThat(service.page(SafeManagementRetirementService.EntityType.PRODUCT,
                25, null, "P-001", null, "code", "asc").items())
                .extracting(ManagementItem::id).containsExactly(ids.productId());
        assertThat(service.page(SafeManagementRetirementService.EntityType.CUSTOMER,
                25, null, "cliente@example.test", null, "email", "asc").items())
                .extracting(ManagementItem::id).containsExactly(ids.customerId());
        assertThat(service.page(SafeManagementRetirementService.EntityType.SUPPLIER,
                25, null, "Distribuciones Test", null, "name", "asc").items())
                .extracting(ManagementItem::id).containsExactly(ids.supplierId());
        assertThat(service.page(SafeManagementRetirementService.EntityType.SALES_REPRESENTATIVE,
                25, null, "WhatsApp", null, "name", "asc").items())
                .extracting(ManagementItem::id).containsExactly(ids.representativeId());

        var firstPage = service.page(SafeManagementRetirementService.EntityType.CUSTOMER,
                1, null, null, null, "code", "desc");
        assertThat(firstPage.items()).extracting(ManagementItem::id).containsExactly(secondCustomerId);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        var secondPage = service.page(SafeManagementRetirementService.EntityType.CUSTOMER,
                1, firstPage.nextCursor(), null, null, "code", "desc");
        assertThat(secondPage.items()).extracting(ManagementItem::id).containsExactly(ids.customerId());
        assertThat(secondPage.hasMore()).isFalse();
    }

    private Fixture insertFixture() {
        var taxId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var channelId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var representativeId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B00000001", "Empresa de prueba", address());
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                storeId, companyId, "Tienda", address(), "hash-" + storeId,
                "Atlantic/Canary", "EUR", "es-ES", "001");
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values (?,?,21)",
                taxId, storeId);
        jdbc.update("insert into familia(id,tienda_id,family_id,family_code,nombre,predeterminada) values (?,?,?,?,?,false)",
                familyId, storeId, "001", "001", "Familia");
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,?)",
                productId, storeId, familyId, taxId, "Producto de prueba");
        jdbc.update("insert into producto_identificador(id,tienda_id,producto_id,tipo,valor) values (?,?,?,?,?)",
                UUID.randomUUID(), storeId, productId, "CODIGO", "P-001");
        jdbc.update("insert into commercial_contact_channel(id,empresa_id,code,name,active) values (?,?,?,'Email',true)",
                channelId, companyId, "EMAIL");
        jdbc.update("""
                insert into cliente(
                    id,empresa_id,client_id,client_code_store_id,nombre_fiscal,
                    tipo_documento,numero_documento,tarifa,descuento,birthday,gender,
                    commercial_consent,preferred_commercial_channel_id,credit_enabled,
                    credit_limit,payment_term_days,credit_blocked,block_on_overdue,
                    telefono,email,poblacion,provincia)
                values (?,?,?,?,?,'CIF',?,'VENTA',12.50,'1990-05-20','OTRO',true,?,false,
                        150.00,45,true,true,'600111222','cliente@example.test','Las Palmas','Las Palmas')
                """, customerId, companyId, "C-001-000001", storeId,
                "Cliente de prueba", "B00000002", channelId);
        jdbc.update("""
                insert into proveedor(
                    id,empresa_id,supplier_id,razon_social,nombre_comercial,
                    tipo_documento,numero_documento,telefono,email,poblacion,provincia)
                values (?,?,?,?,?,'CIF',?,?,?,?,?)
                """, supplierId, companyId, "S-000001", "Proveedor de prueba",
                "Distribuciones Test", "B00000003", "600333444", "proveedor@example.test",
                "Telde", "Las Palmas");
        jdbc.update("""
                insert into comercial(
                    id,empresa_id,commercial_id,nombre,telefono,email,otro_contacto)
                values (?,?,?,?,?,?,?)
                """, representativeId, companyId, "CO-000001", "Comercial de prueba",
                "600555666", "comercial@example.test", "WhatsApp corporativo");
        jdbc.update("insert into proveedor_comercial(proveedor_id,comercial_id,principal) values (?,?,true)",
                supplierId, representativeId);
        return new Fixture(productId, customerId, supplierId, representativeId, channelId);
    }

    private static String address() {
        return "{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\","
                + "\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " no configurada");
        }
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

    private record Fixture(
            UUID productId,
            UUID customerId,
            UUID supplierId,
            UUID representativeId,
            UUID channelId) {
    }
}

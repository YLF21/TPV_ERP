package com.tpverp.backend.inventory;

import static com.tpverp.backend.inventory.SaasProductSalesHistoryTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, SaasProductSalesHistoryService.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("postgresql")
class SaasProductSalesHistoryProductPostgreSqlTest {
    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "tpv_erp_saas_product_history_" + UUID.randomUUID().toString().replace("-", "");
    static { execute("create schema " + SCHEMA); }

    @Autowired SaasProductSalesHistoryService service;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentOrganization organization;
    @MockitoBean SaasProductSalesHistoryClient client;
    @MockitoBean SaasProductSalesHistoryExports exports;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.open-in-view", () -> false);
    }

    @AfterAll static void dropSchema() { execute("drop schema if exists " + SCHEMA + " cascade"); }

    @Test void readsTheDetachedProductCodeWithoutKeepingATransactionDuringTheSaasRequest() {
        UUID productId = fixture();
        var company = mock(Company.class);
        var store = mock(Store.class);
        when(company.getId()).thenReturn(COMPANY);
        when(store.getId()).thenReturn(STORE);
        when(store.getEmpresa()).thenReturn(company);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(client.query(eq("page"), eq(COMPANY), eq(STORE), eq("00042"), anyMap())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return response();
        });

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var detached = products.findWithIdentifiersByStoreIdAndId(STORE, productId).orElseThrow();
        assertThat(detached.getCode()).isEqualTo("00042");
        assertThat(detached.getBarcode()).isEqualTo("9999999999999");
        assertThat(service.page(productId, null, 100, null).path("productCode").asText()).isEqualTo("00042");
        verify(client).query(eq("page"), eq(COMPANY), eq(STORE), eq("00042"), anyMap());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        clearInvocations(client);
        when(store.getId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.page(productId, null, 100, null)).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(client);
    }

    private UUID fixture() {
        UUID family = UUID.randomUUID(), tax = UUID.randomUUID(), product = UUID.randomUUID();
        String address = "{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa (id,tax_id,razon_social,domicilio_fiscal) values (?,?,'Empresa ficticia',cast(? as jsonb))",
                COMPANY, "B12345674", address);
        jdbc.update("insert into tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)"
                        + " values (?,?,'001','Tienda ficticia',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')",
                STORE, COMPANY, address, "fixture-" + STORE);
        jdbc.update("insert into impuesto_tienda (id,tienda_id,porcentaje) values (?,?,21)", tax, STORE);
        jdbc.update("insert into familia (id,tienda_id,family_id,family_code,nombre,predeterminada) values (?,?,'001','001','Familia ficticia',false)",
                family, STORE);
        jdbc.update("insert into producto (id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,'Producto ficticio')",
                product, STORE, family, tax);
        jdbc.update("insert into producto_identificador (id,tienda_id,producto_id,tipo,valor) values (?,?,?,'CODIGO','00042')",
                UUID.randomUUID(), STORE, product);
        jdbc.update("insert into producto_identificador (id,tienda_id,producto_id,tipo,valor) values (?,?,?,'CODIGO_BARRAS','9999999999999')",
                UUID.randomUUID(), STORE, product);
        return product;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " no configurada");
        return value;
    }
    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) { throw new IllegalStateException("No se pudo preparar PostgreSQL aislado", exception); }
    }
}

package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.shared.api.ApiExceptionHandler;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real servlet serialization, method authorization, service and SQL; only the authenticated store is supplied. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, GestionSalesOverviewRepository.class,
        GestionSalesOverviewService.class, GestionSalesOverviewController.class,
        GestionSalesOverviewHttpPostgreSqlTest.MethodSecurityConfiguration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@WithMockUser(authorities = {"APP_GESTION_ACCESS", "GESTION_VENTAS"})
class GestionSalesOverviewHttpPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "gestion_overview_http_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PATH = "/api/v1/gestion/dashboard/data/sales-overview";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 16);

    static { execute("create schema " + SCHEMA); }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoreRepository stores;
    @Autowired private GestionSalesOverviewController controller;
    @MockitoBean private CurrentOrganization organization;
    private MockMvc mvc;
    private UUID storeId;
    private UUID warehouseId;
    private UUID userId;
    private UUID productId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() { execute("drop schema if exists " + SCHEMA + " cascade"); }

    @BeforeEach
    void setUp() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(messages)).build();
        fixture();
        when(organization.currentStore()).thenReturn(stores.findWithCompanyById(storeId).orElseThrow());
    }

    @Test
    void returnsDatabaseTotalsZeroFilledSeriesAndProductRankingWithoutMutatingDocuments() throws Exception {
        var ticket = document(warehouseId, "TICKET", DAY, "100", "10");
        var invoice = document(warehouseId, "FACTURA_VENTA", DAY, "100", "10");
        jdbc.update("insert into documento_relacion(documento_id,origen_id,tipo) values(?,?,'FACTURA_DE')", invoice, ticket);
        document(warehouseId, "RECTIFICATIVA_VENTA", DAY, "-20", "-2");
        var economic = document(warehouseId, "RECTIFICATIVA_VENTA", DAY, "-5", "-1");
        jdbc.update("""
                insert into factura_rectificacion_venta(documento_id,origen_documento_id,tipo_fiscal,
                    metodo,motivo,detalle,afecta_stock,creado_en)
                values(?,?,'R1','I','POST_SALE_DISCOUNT','Test adjustment',false,now())
                """, economic, ticket);
        document(warehouseId, "TICKET", DAY, "0", null);
        document(warehouseId, "TICKET", DAY.minusDays(2), "40", "4");
        var secondWarehouse = UUID.randomUUID();
        jdbc.update("insert into almacen(id,tienda_id,nombre) values(?,?,'SECOND')", secondWarehouse, storeId);
        document(secondWarehouse, "TICKET", DAY, "1000", "100");
        var before = documentSnapshot();

        mvc.perform(get(PATH).param("from", DAY.minusDays(1).toString()).param("to", DAY.toString())
                        .param("warehouseId", warehouseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousFrom").value("2026-09-13"))
                .andExpect(jsonPath("$.previousTo").value("2026-09-14"))
                .andExpect(jsonPath("$.storeTimezone").value("Atlantic/Canary"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.current.netSales").value(75))
                .andExpect(jsonPath("$.current.operationCount").value(4))
                .andExpect(jsonPath("$.current.averageAmount").value(18.75))
                .andExpect(jsonPath("$.previous.netSales").value(40))
                .andExpect(jsonPath("$.previous.operationCount").value(1))
                .andExpect(jsonPath("$.previous.averageAmount").value(40))
                .andExpect(jsonPath("$.daily.length()").value(2))
                .andExpect(jsonPath("$.daily[0].date").value("2026-09-15"))
                .andExpect(jsonPath("$.daily[0].netSales").value(0))
                .andExpect(jsonPath("$.daily[0].operationCount").value(0))
                .andExpect(jsonPath("$.daily[1].netSales").value(75))
                .andExpect(jsonPath("$.daily[1].operationCount").value(4))
                .andExpect(jsonPath("$.previousDaily[0].netSales").value(0))
                .andExpect(jsonPath("$.previousDaily[1].netSales").value(40))
                .andExpect(jsonPath("$.topProducts.length()").value(1))
                .andExpect(jsonPath("$.topProducts[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.topProducts[0].code").value("P001"))
                .andExpect(jsonPath("$.topProducts[0].name").value("Test product"))
                .andExpect(jsonPath("$.topProducts[0].netQuantity").value(8));

        assertThat(documentSnapshot()).isEqualTo(before);
    }

    @Test
    void rejectsARealWarehouseFromAnotherStore() throws Exception {
        var foreignStore = UUID.randomUUID();
        jdbc.update("""
                insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                select ?,empresa_id,'002','Other store',direccion,'other-hash',timezone,moneda,locale from tienda where id=?
                """, foreignStore, storeId);
        var foreignWarehouse = UUID.randomUUID();
        jdbc.update("insert into almacen(id,tienda_id,nombre) values(?,?,'FOREIGN')", foreignWarehouse, foreignStore);
        mvc.perform(get(PATH).param("from", DAY.toString()).param("to", DAY.toString())
                        .param("warehouseId", foreignWarehouse.toString())).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "APP_GESTION_ACCESS")
    void deniesTheRealServiceToUsersWithoutSalesPermission() throws Exception {
        mvc.perform(get(PATH).param("from", DAY.toString()).param("to", DAY.toString()))
                .andExpect(status().isForbidden());
    }

    private void fixture() {
        var companyId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var address = "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values(?,'B00000001','Test company',cast(? as jsonb))",
                companyId, address);
        jdbc.update("""
                insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                values(?,?,'001','Test store',cast(? as jsonb),'hash','Atlantic/Canary','EUR','es-ES')
                """, storeId, companyId, address);
        jdbc.update("insert into almacen(id,tienda_id,nombre) values(?,?,'GENERAL')", warehouseId, storeId);
        jdbc.update("insert into rol(id,tienda_id,nombre) values(?,?,'TEST')", roleId, storeId);
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values(?,?,'TEST','test','fixture-only',?)",
                userId, storeId, roleId);
        jdbc.update("insert into familia(id,tienda_id,nombre) values(?,?,'TEST')", familyId, storeId);
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values(?,?,0)", taxId, storeId);
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values(?,?,?,?,'Test product')",
                productId, storeId, familyId, taxId);
        jdbc.update("insert into producto_identificador(id,tienda_id,producto_id,tipo,valor) values(?,?,?,'CODIGO','P001')",
                UUID.randomUUID(), storeId, productId);
    }

    private UUID document(UUID warehouse, String type, LocalDate date, String total, String quantity) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into documento(id,tienda_id,almacen_id,tipo,estado,fecha,creado_en,confirmado_en,
                    creado_por,confirmado_por,base_total,impuesto_total,total,moneda,origen_stock)
                values(?,?,?,?,'CONFIRMADO',?,now(),now(),?,?,?,0,?,'EUR',false)
                """, id, storeId, warehouse, type, date, userId, userId, new BigDecimal(total), new BigDecimal(total));
        if (quantity != null) {
            jdbc.update("""
                    insert into documento_linea(id,documento_id,producto_id,posicion,cantidad,codigo,nombre,
                        precio_unitario,descuento,impuestos_incluidos,regimen_impuesto,porcentaje_impuesto,base,impuesto,total)
                    values(?,?,?,1,?,'P001','Test product',10,0,true,'IVA',0,?,0,?)
                    """, UUID.randomUUID(), id, productId, new BigDecimal(quantity), new BigDecimal(total), new BigDecimal(total));
        }
        return id;
    }

    private String documentSnapshot() {
        return jdbc.queryForObject("select jsonb_agg(to_jsonb(d) order by id)::text from documento d where tienda_id=?",
                String.class, storeId);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}

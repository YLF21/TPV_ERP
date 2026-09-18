package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class GestionSalesOverviewRepositoryPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "gestion_overview_" + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 16);

    static { execute("create schema " + SCHEMA); }

    @Autowired private JdbcTemplate jdbc;
    private GestionSalesOverviewRepository repository;

    @BeforeEach
    void repository() {
        repository = new GestionSalesOverviewRepository(new NamedParameterJdbcTemplate(jdbc));
    }

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

    @Test
    void sumsSignedLogicalActivityWithoutDuplicatingTicketsOrDroppingInvoicedDeliveryNotes() {
        var fixture = fixture(null, "001");
        var ticket = document(fixture, fixture.warehouseId(), "TICKET", "PAGADO", DAY, "10");
        var invoice = document(fixture, fixture.warehouseId(), "FACTURA_VENTA", "CONFIRMADO", DAY, "10");
        derived(invoice, ticket);
        document(fixture, fixture.warehouseId(), "FACTURA_VENTA", "PENDIENTE", DAY, "20");
        var delivery = document(fixture, fixture.warehouseId(), "ALBARAN_VENTA", "CONFIRMADO", DAY, "40");
        var deliveryInvoice = document(fixture, fixture.warehouseId(), "FACTURA_VENTA", "PAGADO", DAY, "40");
        derived(deliveryInvoice, delivery);
        document(fixture, fixture.warehouseId(), "RECTIFICATIVA_VENTA", "CONFIRMADO", DAY, "2");
        document(fixture, fixture.warehouseId(), "RECTIFICATIVA_VENTA", "CONFIRMADO", DAY, "-4");
        document(fixture, fixture.warehouseId(), "TICKET", "CONFIRMADO", DAY, "-5");
        document(fixture, fixture.warehouseId(), "TICKET", "CONFIRMADO", DAY, "0");
        document(fixture, fixture.warehouseId(), "TICKET", "ANULADO", DAY, "999");
        document(fixture, fixture.warehouseId(), "TICKET", "BORRADOR", DAY, "999");
        document(fixture, fixture.warehouseId(), "FACTURA_COMPRA", "CONFIRMADO", DAY, "999");
        var previousTicket = document(fixture, fixture.warehouseId(), "TICKET", "PAGADO", DAY.minusDays(1), "3");
        var laterInvoice = document(fixture, fixture.warehouseId(), "FACTURA_VENTA", "PAGADO", DAY, "3");
        derived(laterInvoice, previousTicket);

        assertThat(repository.daily(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> {
                    assertThat(row.date()).isEqualTo(DAY);
                    assertThat(row.netSales()).isEqualByComparingTo("63.00");
                    assertThat(row.documentCount()).isEqualTo(7);
                });
        assertThat(repository.daily(fixture.companyId(), fixture.storeId(), null,
                DAY.minusDays(1), DAY)).extracting(GestionSalesOverviewRepository.DayAggregate::date)
                .containsExactly(DAY.minusDays(1), DAY);
    }

    @Test
    void restrictsBothAggregatesByCompanyStoreWarehouseAndInclusiveDocumentDate() {
        var fixture = fixture(null, "001");
        var sameCompanyOtherStore = fixture(fixture.companyId(), "002");
        var otherCompany = fixture(null, "003");
        var secondWarehouse = warehouse(fixture.storeId(), "SECOND");
        var product = product(fixture, "SCOPED");
        line(document(fixture, fixture.warehouseId(), "TICKET", "PAGADO", DAY, "10"), product, "1", 1);
        line(document(fixture, secondWarehouse, "TICKET", "PAGADO", DAY, "20"), product, "2", 1);
        line(document(fixture, fixture.warehouseId(), "TICKET", "PAGADO", DAY.minusDays(1), "30"), product, "3", 1);
        line(document(fixture, fixture.warehouseId(), "TICKET", "PAGADO", DAY.plusDays(1), "40"), product, "4", 1);
        var otherProduct = product(sameCompanyOtherStore, "OTHER");
        line(document(sameCompanyOtherStore, sameCompanyOtherStore.warehouseId(), "TICKET", "PAGADO", DAY, "100"), otherProduct, "100", 1);
        var otherCompanyProduct = product(otherCompany, "FOREIGN");
        line(document(otherCompany, otherCompany.warehouseId(), "TICKET", "PAGADO", DAY, "1000"), otherCompanyProduct, "1000", 1);

        assertThat(repository.daily(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> assertThat(row.netSales()).isEqualByComparingTo("30"));
        assertThat(repository.daily(fixture.companyId(), fixture.storeId(), secondWarehouse, DAY, DAY))
                .singleElement().satisfies(row -> assertThat(row.netSales()).isEqualByComparingTo("20"));
        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), secondWarehouse, DAY, DAY))
                .singleElement().satisfies(row -> assertThat(row.netQuantity()).isEqualByComparingTo("2"));
        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> assertThat(row.netQuantity()).isEqualByComparingTo("3"));
        assertThat(repository.daily(otherCompany.companyId(), fixture.storeId(), null, DAY, DAY)).isEmpty();
        assertThat(repository.topProducts(otherCompany.companyId(), fixture.storeId(), null, DAY, DAY)).isEmpty();
        assertThat(repository.daily(fixture.companyId(), fixture.storeId(), sameCompanyOtherStore.warehouseId(), DAY, DAY)).isEmpty();
        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), sameCompanyOtherStore.warehouseId(), DAY, DAY)).isEmpty();
    }

    @Test
    void ranksNetProductQuantityWithReturnsAndCanonicalDocumentsAndIgnoresAdjustmentLines() {
        var fixture = fixture(null, "001");
        var product = product(fixture, "TOP");
        var returned = product(fixture, "RETURNED");
        var ticket = document(fixture, fixture.warehouseId(), "TICKET", "PAGADO", DAY, "100");
        line(ticket, product, "10.500", 1);
        line(ticket, returned, "1", 2);
        var refund = document(fixture, fixture.warehouseId(), "RECTIFICATIVA_VENTA", "CONFIRMADO", DAY, "-30");
        line(refund, product, "-3.250", 1);
        line(refund, returned, "-2", 2);
        var derivedInvoice = document(fixture, fixture.warehouseId(), "FACTURA_VENTA", "PAGADO", DAY, "100");
        derived(derivedInvoice, ticket);
        line(derivedInvoice, product, "10.500", 1);
        var delivery = document(fixture, fixture.warehouseId(), "ALBARAN_VENTA", "CONFIRMADO", DAY, "40");
        line(delivery, product, "4", 1);
        var invoice = document(fixture, fixture.warehouseId(), "FACTURA_VENTA", "PAGADO", DAY, "40");
        derived(invoice, delivery);
        line(invoice, product, "4", 1);
        line(document(fixture, fixture.warehouseId(), "TICKET", "ANULADO", DAY, "900"), product, "90", 1);
        line(document(fixture, fixture.warehouseId(), "TICKET", "BORRADOR", DAY, "900"), product, "90", 1);
        jdbc.update("""
                insert into documento_linea(id,documento_id,posicion,tipo_linea,cantidad,codigo,nombre,
                    precio_unitario,descuento,impuestos_incluidos,regimen_impuesto,porcentaje_impuesto,base,impuesto,total)
                values(?,?,3,'MANUAL_DISCOUNT',1,'DISCOUNT','Discount',-5,0,true,'IVA',0,-5,0,-5)
                """, UUID.randomUUID(), ticket);

        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> {
                    assertThat(row.productId()).isEqualTo(product);
                    assertThat(row.code()).isEqualTo("TOP");
                    assertThat(row.name()).isEqualTo("Product TOP");
                    assertThat(row.netQuantity()).isEqualByComparingTo("11.250");
                });
    }

    @Test
    void economicRectificationsChangeNetSalesWithoutChangingProductQuantities() {
        var fixture = fixture(null, "001");
        var product = product(fixture, "ECONOMIC");
        var invoice = document(fixture, fixture.warehouseId(), "FACTURA_VENTA", "PAGADO", DAY, "100");
        line(invoice, product, "10", 1);
        var discount = document(fixture, fixture.warehouseId(), "RECTIFICATIVA_VENTA", "CONFIRMADO", DAY, "-5");
        line(discount, product, "-1", 1);
        rectification(discount, invoice, "POST_SALE_DISCOUNT", false);

        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> assertThat(row.netQuantity()).isEqualByComparingTo("10"));

        var priceIncrease = document(fixture, fixture.warehouseId(), "RECTIFICATIVA_VENTA", "CONFIRMADO", DAY, "2");
        line(priceIncrease, product, "1", 1);
        rectification(priceIncrease, invoice, "POST_SALE_PRICE_CHANGE", false);

        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> assertThat(row.netQuantity()).isEqualByComparingTo("10"));

        var returned = document(fixture, fixture.warehouseId(), "RECTIFICATIVA_VENTA", "CONFIRMADO", DAY, "-20");
        line(returned, product, "-2", 1);
        rectification(returned, invoice, "GOODS_RETURN", true);

        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> assertThat(row.netQuantity()).isEqualByComparingTo("8"));
        assertThat(repository.daily(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .singleElement().satisfies(row -> {
                    assertThat(row.netSales()).isEqualByComparingTo("77");
                    assertThat(row.documentCount()).isEqualTo(4);
                });
    }

    @Test
    void capsRankingAtTenAndUsesStableProductOrderForEqualQuantities() {
        var fixture = fixture(null, "001");
        var document = document(fixture, fixture.warehouseId(), "TICKET", "PAGADO", DAY, "100");
        var productIds = new java.util.ArrayList<UUID>();
        for (int index = 0; index < 12; index++) {
            var product = product(fixture, "P" + index);
            productIds.add(product);
            line(document, product, "1", index + 1);
        }
        var expected = productIds.stream().sorted(java.util.Comparator.comparing(UUID::toString)).limit(10).toList();

        assertThat(repository.topProducts(fixture.companyId(), fixture.storeId(), null, DAY, DAY))
                .extracting(GestionSalesOverviewRepository.TopProduct::productId).containsExactlyElementsOf(expected);
    }

    private Fixture fixture(UUID companyId, String code) {
        if (companyId == null) {
            companyId = UUID.randomUUID();
            jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values(?,?,?,cast(? as jsonb))",
                    companyId, "B00000" + code, "Test company " + code, address());
        }
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        jdbc.update("""
                insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                values(?,?,?,?,cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')
                """, storeId, companyId, code, "Store " + code, address(), "hash-" + code);
        jdbc.update("insert into rol(id,tienda_id,nombre) values(?,?,'TEST')", roleId, storeId);
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values(?,?,?,'test','fixture-only',?)",
                userId, storeId, "USER " + code, roleId);
        jdbc.update("insert into familia(id,tienda_id,nombre) values(?,?,'TEST')", familyId, storeId);
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values(?,?,0)", taxId, storeId);
        return new Fixture(companyId, storeId, warehouse(storeId, "GENERAL"), userId, familyId, taxId);
    }

    private UUID warehouse(UUID storeId, String name) {
        var id = UUID.randomUUID();
        jdbc.update("insert into almacen(id,tienda_id,nombre) values(?,?,?)", id, storeId, name);
        return id;
    }

    private UUID product(Fixture fixture, String code) {
        var id = UUID.randomUUID();
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values(?,?,?,?,?)",
                id, fixture.storeId(), fixture.familyId(), fixture.taxId(), "Product " + code);
        jdbc.update("insert into producto_identificador(id,tienda_id,producto_id,tipo,valor) values(?,?,?,'CODIGO',?)",
                UUID.randomUUID(), fixture.storeId(), id, code);
        return id;
    }

    private UUID document(Fixture fixture, UUID warehouseId, String type, String status, LocalDate date, String total) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into documento(id,tienda_id,almacen_id,tipo,estado,fecha,creado_en,confirmado_en,
                    creado_por,confirmado_por,base_total,impuesto_total,total,moneda,origen_stock)
                values(?,?,?,?,?,?,now(),now(),?,?,?,0,?,'EUR',false)
                """, id, fixture.storeId(), warehouseId, type, status, date, fixture.userId(), fixture.userId(),
                new BigDecimal(total), new BigDecimal(total));
        return id;
    }

    private void line(UUID documentId, UUID productId, String quantity, int position) {
        jdbc.update("""
                insert into documento_linea(id,documento_id,producto_id,posicion,cantidad,codigo,nombre,
                    precio_unitario,descuento,impuestos_incluidos,regimen_impuesto,porcentaje_impuesto,base,impuesto,total)
                values(?,?,?,?,?,'HISTORICAL','Historical name',10,0,true,'IVA',0,?,0,?)
                """, UUID.randomUUID(), documentId, productId, position, new BigDecimal(quantity),
                new BigDecimal(quantity).multiply(BigDecimal.TEN), new BigDecimal(quantity).multiply(BigDecimal.TEN));
    }

    private void derived(UUID invoice, UUID origin) {
        jdbc.update("insert into documento_relacion(documento_id,origen_id,tipo) values(?,?,'FACTURA_DE')", invoice, origin);
    }

    private void rectification(UUID document, UUID origin, String reason, boolean affectsStock) {
        jdbc.update("""
                insert into factura_rectificacion_venta(documento_id,origen_documento_id,tipo_fiscal,
                    metodo,motivo,detalle,afecta_stock,creado_en)
                values(?,?,'R1','I',?,'Rectification test',?,now())
                """, document, origin, reason, affectsStock);
    }

    private static String address() {
        return "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
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

    private record Fixture(UUID companyId, UUID storeId, UUID warehouseId, UUID userId, UUID familyId, UUID taxId) {}
}

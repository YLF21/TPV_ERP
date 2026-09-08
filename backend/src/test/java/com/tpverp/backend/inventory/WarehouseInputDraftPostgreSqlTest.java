package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FlywayPostgreSqlConfiguration.class, WarehouseInputService.class,
        WarehouseInputDraftPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarehouseInputDraftPostgreSqlTest {
    private static final String URL = environment("TPV_ERP_TEST_DB_URL");
    private static final String USER = environment("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = environment("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "warehouse_draft_" + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 8);
    private static final String ADDRESS = "{\"linea1\":\"Calle Uno\",\"ciudad\":\"Madrid\",\"codigoPostal\":\"28001\",\"provincia\":\"Madrid\",\"pais\":\"ES\"}";

    static {
        if (!URL.isBlank()) {
            if (!URL.matches("jdbc:postgresql://[^/]+/tpv_erp_[a-z_]*test(?:\\?.*)?")) {
                throw new IllegalStateException("Warehouse draft tests require an isolated tpv_erp_*test database");
            }
            execute("create schema " + SCHEMA);
        }
    }

    @Autowired WarehouseInputService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentOrganization organization;
    @MockitoBean StockMovementSyncPublisher syncPublisher;
    @MockitoBean WarehouseInputExcelAuditService excelAudit;
    @MockitoBean WarehouseExcelImportProvenanceService provenance;

    private UUID storeId;
    private UUID warehouseId;
    private UUID supplierId;
    private UUID firstProduct;
    private UUID secondProduct;
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("ADMIN", "test",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        if (!URL.isBlank()) execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @BeforeEach
    void fixture() {
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        firstProduct = UUID.randomUUID();
        secondProduct = UUID.randomUUID();
        jdbc.update("insert into empresa (id,tax_id,razon_social,domicilio_fiscal) values (?,?,'Draft test',cast(? as jsonb))",
                companyId, "B" + companyId.toString().replace("-", "").substring(0, 8), ADDRESS);
        jdbc.update("""
                insert into tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale)
                values (?,?,'001','Draft test',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')
                """, storeId, companyId, ADDRESS, "draft-" + storeId);
        jdbc.update("insert into rol (id,tienda_id,nombre,protegido) values (?,?,'ADMIN',true)", roleId, storeId);
        jdbc.update("insert into usuario (id,tienda_id,nombre,user_name,password_hash,rol_id,protegido) values (?,?,'ADMIN','ADMIN','hash',?,true)", userId, storeId, roleId);
        jdbc.update("insert into impuesto_tienda (id,tienda_id,porcentaje) values (?,?,21)", taxId, storeId);
        jdbc.update("insert into familia (id,tienda_id,nombre) values (?,?,'GENERAL')", familyId, storeId);
        jdbc.update("insert into almacen (id,tienda_id,nombre,predeterminado) values (?,?,'GENERAL',true)", warehouseId, storeId);
        jdbc.update("insert into proveedor (id,empresa_id,supplier_id,razon_social,tipo_documento,numero_documento) values (?,?,'S-000001','Supplier test','NIF','B12345678')", supplierId, companyId);
        for (UUID productId : List.of(firstProduct, secondProduct)) {
            jdbc.update("insert into producto (id,tienda_id,familia_id,impuesto_id,nombre,precio_compra) values (?,?,?,?,'Producto',1.104)", productId, storeId, familyId, taxId);
        }
        var store = mock(Store.class);
        var company = mock(Company.class);
        var user = mock(UserAccount.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(company.getId()).thenReturn(companyId);
        when(user.getId()).thenReturn(userId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentUser(authentication)).thenReturn(user);
    }

    @Test
    void importedSupplierIntentSurvivesSavesAndConfirmationButCannotBeSilentlyCleared() {
        var metadata = new WarehouseExcelImportMetadata("suppliers.xlsx", List.of(), "a".repeat(64), "Sheet1", true, true,
                List.of(new WarehouseExcelImportMetadata.Line(firstProduct, List.of(2), "REF-1", new BigDecimal("1.104"), new BigDecimal("20")),
                        new WarehouseExcelImportMetadata.Line(secondProduct, List.of(3), "REF-2", new BigDecimal("2.208"), BigDecimal.ZERO)));
        when(provenance.verifyApply(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(provenance.verifyDocument(any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), anyList())).thenReturn(true);
        when(provenance.signDocument(any(), any(), any(), any(), any(), anyLong(), any(), any(), anyList())).thenReturn("WXP1.D." + "d".repeat(512));
        var original = List.of(line(firstProduct, "6", "1.104"), line(secondProduct, "12", "2.208"));
        var draft = service.createView(new WarehouseInputCommand(warehouseId, DATE, supplierId, "Supplier", null, null,
                WarehouseInputDocumentType.FACTURA_ENTRADA, WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(),
                original, metadata, "WXP1.A." + "a".repeat(512), false, null), authentication);
        for (int attempt = 0; attempt < 3; attempt++) {
            // Force a different physical retrieval order; business order is position.
            jdbc.update("update entrada_almacen_linea set nombre_producto=nombre_producto where entrada_id=? and posicion=1", draft.id());
            draft = service.updateView(draft.id(), importedCommand(original, draft.excelImportSnapshotToken(), false));
            assertThat(draft.excelImportPendingSupplierUpdate()).isTrue();
            assertThat(draft.lines()).extracting(WarehouseInputLineView::productId).containsExactly(firstProduct, secondProduct);
        }
        var id = draft.id();
        var snapshot = draft.excelImportSnapshotToken();
        var before = lines(id);
        assertThatThrownBy(() -> service.updateView(id, importedCommand(original, null, true)))
                .hasMessage("EXCEL_IMPORT_REVIEW_REQUIRED");
        assertThatThrownBy(() -> service.updateView(id, importedCommand(List.of(line(firstProduct, "7", "1.104"), original.get(1)), snapshot, false)))
                .hasMessage("EXCEL_IMPORT_REVIEW_REQUIRED");
        assertThat(lines(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from producto_proveedor where proveedor_id=?", Integer.class, supplierId)).isZero();
        assertThatThrownBy(() -> service.confirmView(id, new UsernamePasswordAuthenticationToken("warehouse-only", "test")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(movementCount(id)).isZero();
        jdbc.execute("create sequence supplier_insert_attempts");
        jdbc.execute("""
                create function fail_second_supplier_insert() returns trigger language plpgsql as $$
                begin
                    if new.proveedor_id = '%s'::uuid and nextval('supplier_insert_attempts') = 2 then
                        raise exception 'forced supplier insertion failure';
                    end if;
                    return new;
                end $$
                """.formatted(supplierId));
        jdbc.execute("create trigger fail_supplier_insert before insert on producto_proveedor for each row execute function fail_second_supplier_insert()");
        try {
            assertThatThrownBy(() -> service.confirmView(id, authentication)).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("forced supplier insertion failure");
            assertThat(movementCount(id)).isZero();
            assertThat(jdbc.queryForObject("select estado from entrada_almacen where id=?", String.class, id)).isEqualTo("BORRADOR");
            assertThat(jdbc.queryForObject("select count(*) from producto_proveedor where proveedor_id=?", Integer.class, supplierId)).isZero();
        } finally {
            jdbc.execute("drop trigger fail_supplier_insert on producto_proveedor");
        }
        service.confirmView(id, authentication);
        assertThat(jdbc.queryForObject("select count(*) from producto_proveedor where proveedor_id=?", Integer.class, supplierId)).isEqualTo(2);
        var link = jdbc.queryForMap("select * from producto_proveedor where producto_id=? and proveedor_id=?", firstProduct, supplierId);
        assertThat(link.get("referencia_proveedor")).isEqualTo("REF-1");
        assertThat((BigDecimal) link.get("precio_compra_bruto")).isEqualByComparingTo("1.104");
        assertThat((BigDecimal) link.get("descuento_compra")).isEqualByComparingTo("20");
        assertThat(link.get("principal")).isEqualTo(false);
        assertThat(link.get("ultimo_proveedor")).isEqualTo(true);
        assertThat(movementCount(id)).isEqualTo(2);
        assertThatThrownBy(() -> service.confirmView(id, authentication)).isInstanceOf(IllegalStateException.class);
        assertThat(movementCount(id)).isEqualTo(2);
    }

    private WarehouseInputCommand importedCommand(List<WarehouseInputLineCommand> importedLines, String snapshot, boolean clear) {
        return new WarehouseInputCommand(warehouseId, DATE, supplierId, "Supplier edited label", "EXT-1", "Reviewed comment",
                WarehouseInputDocumentType.FACTURA_ENTRADA, WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(),
                importedLines, null, null, clear, snapshot);
    }

    @Test
    void resavesAnExistingInvoiceBeforeConfirmingAndDoesNotDuplicateStock() {
        var command = command(List.of(line(firstProduct, "6", "1.104"), line(secondProduct, "12", "2.208")));
        var draft = service.createView(command, authentication);
        service.updateView(draft.id(), command);
        service.updateView(draft.id(), command);
        assertThat(movementCount(draft.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from existencia where almacen_id=?", Integer.class, warehouseId)).isZero();

        service.confirmView(draft.id(), authentication);

        assertThat(jdbc.queryForObject("select estado from entrada_almacen where id=?", String.class, draft.id())).isEqualTo("CONFIRMADA");
        assertThat(jdbc.queryForObject("select cantidad from existencia where almacen_id=? and producto_id=?", BigDecimal.class, warehouseId, firstProduct)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("select cantidad from existencia where almacen_id=? and producto_id=?", BigDecimal.class, warehouseId, secondProduct)).isEqualByComparingTo("12");
        assertThat(movementCount(draft.id())).isEqualTo(2);
        assertThatThrownBy(() -> service.confirmView(draft.id(), authentication)).isInstanceOf(IllegalStateException.class);
        assertThat(movementCount(draft.id())).isEqualTo(2);
        var before = lines(draft.id());
        assertThatThrownBy(() -> service.updateView(draft.id(), command)).isInstanceOf(IllegalStateException.class);
        assertThat(lines(draft.id())).isEqualTo(before);
    }

    @Test
    void replacesReorderedShrunkAndExpandedLinesWithoutPositionCollisions() {
        var draft = service.createView(command(List.of(line(firstProduct, "1", "1.104"), line(secondProduct, "2", "2.208"))), authentication);
        service.updateView(draft.id(), command(List.of(line(secondProduct, "3", "3.333"), line(firstProduct, "4", "0"))));
        assertThat(lines(draft.id())).extracting(row -> row.get("producto_id")).containsExactly(secondProduct, firstProduct);
        service.updateView(draft.id(), command(List.of(line(firstProduct, "5", "1.235"))));
        assertThat(lines(draft.id())).hasSize(1);
        var many = IntStream.range(0, 394).mapToObj(index -> line(index % 2 == 0 ? firstProduct : secondProduct, "1", "2.208")).toList();
        service.updateView(draft.id(), command(many));
        service.updateView(draft.id(), command(many));
        assertThat(lines(draft.id())).hasSize(394).extracting(row -> row.get("posicion"))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 394).boxed().toList());
        assertThat(lines(draft.id())).allSatisfy(row -> assertThat((BigDecimal) row.get("precio_unitario_compra")).isEqualByComparingTo("2.208"));
        assertThat(movementCount(draft.id())).isZero();
    }

    @Test
    void rollsBackTheDeletionAndFirstInsertionIfTheSecondInsertionFails() {
        var draft = service.createView(command(List.of(line(firstProduct, "6", "1.104"), line(secondProduct, "12", "2.208"))), authentication);
        var originalLines = lines(draft.id());
        var originalHeader = jdbc.queryForMap("select * from entrada_almacen where id=?", draft.id());
        jdbc.execute("create sequence draft_insert_attempts");
        jdbc.execute("""
                create function draft_fail_second_insert() returns trigger language plpgsql as $$
                begin
                    if new.entrada_id = '%s'::uuid and nextval('draft_insert_attempts') = 2 then
                        raise exception 'forced second draft insertion failure';
                    end if;
                    return new;
                end $$
                """.formatted(draft.id()));
        jdbc.execute("create trigger draft_fail_insert before insert on entrada_almacen_linea for each row execute function draft_fail_second_insert()");
        try {
            assertThatThrownBy(() -> service.updateView(draft.id(), command(List.of(
                    line(secondProduct, "100", "9.999"), line(firstProduct, "200", "8.888")))))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("forced second draft insertion failure");
            assertThat(jdbc.queryForObject("select last_value from draft_insert_attempts", Long.class)).isEqualTo(2);
            assertThat(lines(draft.id())).isEqualTo(originalLines);
            assertThat(jdbc.queryForMap("select * from entrada_almacen where id=?", draft.id())).isEqualTo(originalHeader);
            assertThat(movementCount(draft.id())).isZero();
        } finally {
            jdbc.execute("drop trigger draft_fail_insert on entrada_almacen_linea");
        }
        service.updateView(draft.id(), command(List.of(line(firstProduct, "7", "1.104"))));
        assertThat(lines(draft.id())).hasSize(1);
    }

    private WarehouseInputCommand command(List<WarehouseInputLineCommand> lines) {
        return new WarehouseInputCommand(warehouseId, DATE, supplierId, "Supplier", "TEST-INVOICE", "Draft test",
                WarehouseInputDocumentType.FACTURA_ENTRADA, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), lines, null);
    }

    private static WarehouseInputLineCommand line(UUID productId, String quantity, String price) {
        return new WarehouseInputLineCommand(productId, new BigDecimal(quantity), new BigDecimal(price), BigDecimal.ZERO, true, "Producto");
    }

    private List<Map<String, Object>> lines(UUID id) {
        return jdbc.queryForList("select * from entrada_almacen_linea where entrada_id=? order by posicion", id);
    }

    private int movementCount(UUID id) {
        return jdbc.queryForObject("select count(*) from movimiento_stock where entrada_almacen_id=?", Integer.class, id);
    }

    private static String environment(String name) {
        return System.getenv().getOrDefault(name, "");
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo preparar PostgreSQL aislado", exception);
        }
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary Clock clock() { return Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC); }
    }
}

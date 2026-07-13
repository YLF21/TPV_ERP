package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.catalog.ProductSupplierRepository;
import com.tpverp.backend.inventory.InventoryDocumentGateway;
import com.tpverp.backend.inventory.StockMovementSyncPublisher;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.promotion.PromotionEngine;
import com.tpverp.backend.promotion.PromotionalCouponService;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.verifactu.FiscalDocumentPolicy;
import com.tpverp.backend.verifactu.FiscalRecordService;
import com.tpverp.backend.verifactu.FiscalSnapshotFactory;
import com.tpverp.backend.verifactu.VerifactuActivationService;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DocumentService.class, InventoryDocumentGateway.class, StockMovementSyncPublisher.class,
        SyncOutboxService.class, DocumentFiscalIntegration.class, FiscalRecordService.class,
        VerifactuActivationService.class, FiscalSnapshotFactory.class, FiscalDocumentPolicy.class,
        PaymentTerminalRefundDocumentPostgreSqlTest.Configuration.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentTerminalRefundDocumentPostgreSqlTest {
    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "tpv_refund_document_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-07-13T10:00:00Z");

    static { execute("create schema " + SCHEMA); }

    @Autowired private DocumentService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CompanyRepository companies;
    @Autowired private StoreRepository stores;
    @Autowired private UserAccountRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private CurrentTerminal currentTerminal;
    @MockitoBean private ConfirmedPurchaseRecorder purchaseRecorder;
    @MockitoBean private VoucherService vouchers;
    @MockitoBean private CashPaymentRecorder cashPayments;
    @MockitoBean private MemberLoyaltyService memberLoyalty;
    @MockitoBean private PromotionEngine promotionEngine;
    @MockitoBean private PromotionalCouponService promotionalCoupons;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach void clearDatabase() { jdbc.execute("truncate table instalacion, empresa cascade"); }
    @AfterAll static void dropSchema() { execute("drop schema if exists " + SCHEMA + " cascade"); }

    @Test
    void fullRefundPersistsOneNegativeDocumentRectificationStockFiscalRecordAndOutboxAcrossReplay() {
        var fixture = insertFixture();
        var company = companies.findById(fixture.companyId()).orElseThrow();
        var store = stores.findById(fixture.storeId()).orElseThrow();
        var user = users.findById(fixture.userId()).orElseThrow();
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(any())).thenReturn(user);
        when(currentTerminal.terminalId(any())).thenReturn(fixture.terminalId());
        var authentication = new UsernamePasswordAuthenticationToken("ADMIN", "n/a");

        var first = inTransaction(() -> service.createApprovedCardRefund(
                fixture.operationId(), fixture.documentId(), new BigDecimal("12.10"), authentication));
        var replay = inTransaction(() -> service.createApprovedCardRefund(
                fixture.operationId(), fixture.documentId(), new BigDecimal("12.10"), authentication));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject("select count(*) from documento where payment_terminal_refund_operation_id = ?", Integer.class, fixture.operationId())).isEqualTo(1);
        assertThat(jdbc.queryForMap("select tipo, estado, total, origen_stock from documento where id = ?", first.getId()))
                .containsEntry("tipo", "TICKET").containsEntry("estado", "CONFIRMADO")
                .containsEntry("total", new BigDecimal("-12.10")).containsEntry("origen_stock", true);
        assertThat(jdbc.queryForObject("select count(*) from documento_relacion where documento_id = ? and origen_id = ? and tipo = 'RECTIFICA'", Integer.class, first.getId(), fixture.documentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cantidad from existencia where producto_id = ? and almacen_id = ?", BigDecimal.class, fixture.productId(), fixture.warehouseId())).isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_stock where documento_id = ? and tipo = 'TICKET' and cantidad = 1", Integer.class, first.getId())).isEqualTo(1);
        assertThat(jdbc.queryForMap("select operacion, tipo_documento_fiscal, importe_total, cuota_total from registro_fiscal where documento_id = ?", first.getId()))
                .containsEntry("operacion", "ALTA").containsEntry("tipo_documento_fiscal", "R5")
                .containsEntry("importe_total", new BigDecimal("-12.10")).containsEntry("cuota_total", new BigDecimal("-2.10"));
        assertThat(jdbc.queryForObject("select count(*) from estado_envio_fiscal where registro_id = (select id from registro_fiscal where documento_id = ?)", Integer.class, first.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox where (tipo_entidad = 'DOCUMENTO' and entidad_id = ?) or (tipo_entidad = 'STOCK_MOVEMENT' and payload ->> 'documentoId' = ?)", Integer.class, first.getId(), first.getId().toString())).isEqualTo(2);
    }

    private Fixture insertFixture() {
        var f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var address = "{\"linea1\":\"a\",\"ciudad\":\"c\",\"codigoPostal\":\"35001\",\"provincia\":\"p\",\"pais\":\"ES\"}";
        jdbc.update("insert into instalacion(id,referencia,public_key,creada_en,demo_hasta) values (?,'TEST','key',?,?)", f.installationId(), java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), java.sql.Timestamp.from(Instant.parse("2026-01-31T00:00:00Z")));
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,'B12345674','Company',cast(? as jsonb))", f.companyId(), address);
        jdbc.update("insert into tienda(id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale) values (?,?,'001','Store',cast(? as jsonb),'h','Atlantic/Canary','EUR','es-ES')", f.storeId(), f.companyId(), address);
        jdbc.update("insert into licencia(id,tienda_id,instalacion_id,referencia,valida_desde,valida_hasta,max_windows,max_pda,tax_id,taxpayer_type,regimen_impuesto,blob_original,hash,format_version,importada_en,ultima_validacion_saas,import_result,activa) values (?,?,?,'LIC',?,?,1,0,'B12345674','SOCIEDAD','IGIC','blob','hash',3,?,?,'ACEPTADA',true)", UUID.randomUUID(), f.storeId(), f.installationId(), java.sql.Timestamp.from(NOW.minusSeconds(100)), java.sql.Timestamp.from(NOW.plusSeconds(100)), java.sql.Timestamp.from(NOW.minusSeconds(100)), java.sql.Timestamp.from(NOW.minusSeconds(100)));
        jdbc.update("insert into configuracion_verifactu(id,empresa_id,activacion_voluntaria,activada_en) values (?,?,true,?)", UUID.randomUUID(), f.companyId(), java.sql.Timestamp.from(NOW.minusSeconds(100)));
        jdbc.update("insert into rol(id,tienda_id,nombre,protegido) values (?,?,'ADMIN',true)", f.roleId(), f.storeId());
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id,protegido) values (?,?,'ADMIN','ADMIN','hash',?,true)", f.userId(), f.storeId(), f.roleId());
        jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values (?,?,'T','TERMINAL_VENTA','h')", f.terminalId(), f.storeId());
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values (?,?,21)", f.taxId(), f.storeId());
        jdbc.update("insert into familia(id,tienda_id,nombre) values (?,?,'GENERAL')", f.familyId(), f.storeId());
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado) values (?,?,'GENERAL',true)", f.warehouseId(), f.storeId());
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values (?,?,?,?,'Producto')", f.productId(), f.storeId(), f.familyId(), f.taxId());
        jdbc.update("insert into documento(id,tienda_id,almacen_id,tipo,estado,numero,fecha,creado_en,confirmado_en,creado_por,confirmado_por,descuento_global,base_total,impuesto_total,total,moneda,origen_stock) values (?,?,?,'TICKET','CONFIRMADO','001-260713-000001',?,?,?,?,?,0,10,2.10,12.10,'EUR',false)", f.documentId(), f.storeId(), f.warehouseId(), LocalDate.of(2026,7,13), java.sql.Timestamp.from(NOW.minusSeconds(60)), java.sql.Timestamp.from(NOW.minusSeconds(30)), f.userId(), f.userId());
        jdbc.update("insert into documento_linea(id,documento_id,producto_id,posicion,cantidad,codigo,nombre,tarifa,precio_unitario,descuento,impuestos_incluidos,regimen_impuesto,porcentaje_impuesto,base,impuesto,total,tipo_linea) values (?,?,?,1,1,'P-1','Producto','VENTA',12.10,0,true,'IVA',21,10,2.10,12.10,'PRODUCT')", UUID.randomUUID(), f.documentId(), f.productId());
        jdbc.update("insert into payment_terminal_operation(id,terminal_id,store_id,provider,mode,operation_type,idempotency_key,request_hash,amount,status,external_reference,authorization_code,configuration_version,document_id,created_at,updated_at,completed_at) values (?,?,?,'PAYTEF','SIMULATED','CHARGE','charge','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',12.10,'APPROVED','CHARGE-REF','AUTH',-1,?,?,?,?)", f.chargeOperationId(), f.terminalId(), f.storeId(), f.documentId(), java.sql.Timestamp.from(NOW.minusSeconds(60)), java.sql.Timestamp.from(NOW.minusSeconds(30)), java.sql.Timestamp.from(NOW.minusSeconds(30)));
        jdbc.update("insert into payment_terminal_operation(id,terminal_id,store_id,provider,mode,operation_type,original_operation_id,idempotency_key,request_hash,amount,status,external_reference,authorization_code,configuration_version,created_at,updated_at,completed_at) values (?,?,?,'PAYTEF','SIMULATED','REFUND',?,'refund','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',12.10,'APPROVED','REFUND-REF','AUTH-R',-1,?,?,?)", f.operationId(), f.terminalId(), f.storeId(), f.chargeOperationId(), java.sql.Timestamp.from(NOW.minusSeconds(20)), java.sql.Timestamp.from(NOW.minusSeconds(10)), java.sql.Timestamp.from(NOW.minusSeconds(10)));
        return f;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) { return new TransactionTemplate(transactionManager).execute(status -> action.get()); }
    private static String required(String name) { var value=System.getenv(name); if(value==null||value.isBlank()) throw new IllegalStateException(name+" no configurada"); return value; }
    private static void execute(String sql) { try(var connection=DriverManager.getConnection(URL,USER,PASSWORD);var statement=connection.createStatement()){statement.execute(sql);}catch(Exception exception){throw new IllegalStateException(exception);} }

    @TestConfiguration static class Configuration { @Bean @Primary Clock clock(){return Clock.fixed(NOW, ZoneOffset.UTC);} }
    private record Fixture(UUID installationId, UUID companyId, UUID storeId, UUID roleId, UUID userId, UUID terminalId, UUID taxId, UUID familyId, UUID warehouseId, UUID productId, UUID documentId, UUID chargeOperationId, UUID operationId) { }
}

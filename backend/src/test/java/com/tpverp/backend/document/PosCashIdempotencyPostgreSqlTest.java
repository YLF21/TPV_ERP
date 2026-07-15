package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PosCashService.class, PosCashTicketSnapshot.class})
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class PosCashIdempotencyPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "cash_idempotency_" + UUID.randomUUID().toString().replace("-", "");

    static {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("create schema " + SCHEMA);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + "?currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Autowired private PosCashService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private DocumentService documents;
    @MockitoBean private ProductRepository products;
    @MockitoBean private StoreTaxRepository taxes;
    @MockitoBean private WarehouseRepository warehouses;
    @MockitoBean private PaymentMethodRepository paymentMethods;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private CurrentTerminal currentTerminal;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousIdenticalChargesCreateOneTicketAndReplayItsPersistedSnapshot() throws Exception {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var cashId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "B1", "Test", "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}");
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                storeId, companyId, "T", "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}", "h", "Europe/Madrid", "EUR", "es-ES", "001");
        jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values (?,?,?,?,?)",
                terminalId, storeId, "TPV", "TERMINAL_VENTA", "h");
        jdbc.update("insert into rol(id,tienda_id,nombre) values (?,?,?)", roleId, storeId, "SELLER");
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values (?,?,?,?,?,?)",
                userId, storeId, "SELLER", "Seller", "h", roleId);
        jdbc.update("insert into almacen(id,tienda_id,nombre,predeterminado) values (?,?,?,true)", warehouseId, storeId, "A");
        jdbc.update("insert into metodo_pago(id,empresa_id,nombre) values (?,?,?)", cashId, companyId, "EFECTIVO");

        var store = mock(Store.class);
        var company = mock(Company.class);
        var user = mock(UserAccount.class);
        var product = mock(Product.class);
        var warehouse = mock(Warehouse.class);
        var tax = mock(StoreTax.class);
        var cash = mock(PaymentMethod.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(company.getId()).thenReturn(companyId);
        when(user.getId()).thenReturn(userId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(currentTerminal.terminalId(any())).thenReturn(terminalId);
        when(warehouse.getId()).thenReturn(warehouseId);
        when(warehouse.isActive()).thenReturn(true);
        when(warehouses.findByStoreIdAndPredeterminadoTrue(storeId)).thenReturn(Optional.of(warehouse));
        when(product.getId()).thenReturn(productId);
        when(product.getStoreId()).thenReturn(storeId);
        when(product.getTaxId()).thenReturn(taxId);
        when(product.getCode()).thenReturn("CAFE");
        when(product.getName()).thenReturn("Cafe");
        when(product.getSalePrice()).thenReturn(new BigDecimal("7.00"));
        when(product.isTaxesIncluded()).thenReturn(true);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(true);
        when(tax.getPercentage()).thenReturn(new BigDecimal("21"));
        when(taxes.findById(taxId)).thenReturn(Optional.of(tax));
        when(cash.getId()).thenReturn(cashId);
        when(paymentMethods.findByEmpresaIdAndNombreAndActivoTrue(companyId, "EFECTIVO"))
                .thenReturn(Optional.of(cash));

        when(documents.quoteTicket(any(), any())).thenAnswer(invocation -> {
            var quote = mock(CommercialDocument.class);
            when(quote.getTotal()).thenReturn(new BigDecimal("7.00"));
            return quote;
        });
        when(documents.createTicket(any(), anyList(), any())).thenAnswer(invocation -> {
            var ticket = new CommercialDocument(storeId, warehouseId, CommercialDocumentType.TICKET,
                    LocalDate.of(2026, 7, 15), userId, BigDecimal.ZERO);
            ticket.addLine(new DocumentLine(ticket, productId, 1, BigDecimal.ONE, "CAFE", "Cafe",
                    null, new BigDecimal("7.00"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21")));
            ticket.confirm("T-CASH-1", userId, Instant.parse("2026-07-15T10:15:30Z"), false);
            ticket.addPayment(new DocumentPayment(ticket, cash, 1, new BigDecimal("7.00"), true,
                    new BigDecimal("10.00"), new BigDecimal("3.00"), Instant.parse("2026-07-15T10:15:30Z")));
            jdbc.update("insert into documento(id,tienda_id,almacen_id,tipo,estado,numero,fecha,creado_en,creado_por,total) values (?,?,?,'TICKET','CONFIRMADO',?,current_date,now(),?,?)",
                    ticket.getId(), storeId, warehouseId, ticket.getNumero(), userId, ticket.getTotal());
            return ticket;
        });

        var auth = new UsernamePasswordAuthenticationToken(user, "n/a");
        var request = new PosCashController.CashRequest(UUID.randomUUID(),
                new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO))),
                new BigDecimal("10.00"), new BigDecimal("7.00"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.charge(request, auth); });
            var second = pool.submit(() -> { start.await(); return service.charge(request, auth); });
            start.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(a.printTicket()).isEqualTo(b.printTicket());
        }
        verify(documents, times(1)).createTicket(any(), anyList(), any());
        assertThat(jdbc.queryForObject("select count(*) from pos_cash_checkout", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from documento where numero='T-CASH-1'", Integer.class)).isEqualTo(1);
    }
}

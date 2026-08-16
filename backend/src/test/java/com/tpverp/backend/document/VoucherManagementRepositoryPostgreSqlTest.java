package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VoucherManagementRepositoryPostgreSqlTest {

    private static final String URL = environment(
            "TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    private static final String USER = environment("TPV_TEST_DB_USERNAME", "tpv_erp_test");
    private static final String PASSWORD = environment("TPV_TEST_DB_PASSWORD", "admin");
    private static final String SCHEMA =
            "voucher_management_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 16);

    static {
        execute("create schema " + SCHEMA);
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
    static void cleanup() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Autowired private VoucherRepository vouchers;
    @Autowired private VoucherFamilyRepository families;
    @Autowired private StoreVoucherConfigurationRepository configurations;
    @Autowired private VoucherManagementEventRepository events;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void executesNullFiltersAndSeparatesActiveFromExpired() {
        var storeId = insertStore();
        vouchers.saveAllAndFlush(List.of(
                voucher(storeId, 1, "VA-ACTIVE", "25.00",
                        "T-100", NOW.minusSeconds(3600), TODAY),
                voucher(storeId, 2, "VA-EXPIRED", "12.00",
                        "T-200", NOW.minusSeconds(7200), TODAY.minusDays(1))));

        assertThat(vouchers.findManagementPage(
                storeId, null, null, null, null, TODAY, PageRequest.of(0, 50)).getContent())
                .extracting(Voucher::code)
                .containsExactly("VA-ACTIVE", "VA-EXPIRED");
        assertThat(vouchers.findManagementPage(
                storeId, null, "ACTIVE", null, null, TODAY, PageRequest.of(0, 50)).getContent())
                .extracting(Voucher::code)
                .containsExactly("VA-ACTIVE");
        assertThat(vouchers.findManagementPage(
                storeId, null, "EXPIRED", null, null, TODAY, PageRequest.of(0, 50)).getContent())
                .extracting(Voucher::code)
                .containsExactly("VA-EXPIRED");
    }

    @Test
    void searchesVoucherCodeAndOriginTicketCaseInsensitively() {
        var storeId = insertStore();
        vouchers.saveAndFlush(voucher(storeId, 1, "VA-AbC123", "25.00",
                "TK-9001", NOW.minusSeconds(3600), TODAY));

        assertThat(vouchers.findManagementPage(
                storeId, "abc", null, null, null, TODAY, PageRequest.of(0, 50)).getContent())
                .extracting(Voucher::code)
                .containsExactly("VA-AbC123");
        assertThat(vouchers.findManagementPage(
                storeId, "tk-9001", null, null, null, TODAY, PageRequest.of(0, 50)).getContent())
                .extracting(Voucher::code)
                .containsExactly("VA-AbC123");
        assertThat(vouchers.findManagementPage(
                storeId, "001-000001", null, null, null, TODAY,
                PageRequest.of(0, 50)).getContent())
                .extracting(Voucher::code)
                .containsExactly("VA-AbC123");
    }

    @Test
    void recordsAndReturnsTheAuditedReprintInOneOperation() {
        var storeId = insertStore();
        var userId = insertUser(storeId);
        var voucher = vouchers.saveAndFlush(voucher(
                storeId, 1, "VA-PRINT", "25.00", "T-PRINT", NOW, TODAY));
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var authentication = mock(Authentication.class);
        var store = mock(Store.class);
        var operator = mock(UserAccount.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(operator);
        when(operator.getId()).thenReturn(userId);
        when(operator.getUserName()).thenReturn("ADMIN");
        when(currentTerminal.terminalId(authentication))
                .thenThrow(new IllegalStateException("No management terminal"));
        var service = new VoucherManagementService(
                vouchers, configurations, events, mock(VoucherPrintService.class),
                organization, currentTerminal, mock(AuditService.class),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        var detail = service.recordPrintResult("VA-PRINT", true, authentication);

        assertThat(detail.voucher().code()).isEqualTo("VA-PRINT");
        assertThat(detail.events())
                .extracting(VoucherManagementService.ManagementEventView::type)
                .containsExactly(VoucherManagementEventType.REPRINTED);
        assertThat(events.findAllByVoucher_IdOrderByOccurredAtDesc(voucher.id()))
                .extracting(VoucherManagementEvent::type)
                .containsExactly(VoucherManagementEventType.REPRINTED);
    }

    @Test
    void allocatesIndependentSixDigitSequencesPerStore() {
        var firstStore = insertStore();
        var secondStore = insertStore();
        var familyNumbers = new VoucherFamilyNumberAllocator(jdbc);

        assertThat(familyNumbers.next(firstStore)).isEqualTo(1);
        assertThat(familyNumbers.next(firstStore)).isEqualTo(2);
        assertThat(familyNumbers.next(secondStore)).isEqualTo(1);
    }

    private UUID insertStore() {
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                companyId, "TEST-" + companyId, "Test", "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}");
        jdbc.update("insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,timezone,moneda,locale,codigo_tienda) values (?,?,?,cast(? as jsonb),?,?,?,?,?)",
                storeId, companyId, "T", "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}",
                "h", "Atlantic/Canary", "EUR", "es-ES", "001");
        return storeId;
    }

    private UUID insertUser(UUID storeId) {
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        jdbc.update("insert into rol(id,tienda_id,nombre,protegido,version) values (?,?,?,?,0)",
                roleId, storeId, "ADMIN-PRINT", false);
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id,protegido,activo,idioma,version) values (?,?,?,?,?,?,?,?,?,0)",
                userId, storeId, "ADMIN-PRINT", "ADMIN", "test", roleId,
                false, true, "ES");
        return userId;
    }

    private Voucher voucher(
            UUID storeId,
            int sequence,
            String code,
            String amount,
            String originTicket,
            Instant createdAt,
            LocalDate expiresOn) {
        var companyId = jdbc.queryForObject(
                "select empresa_id from tienda where id = ?", UUID.class, storeId);
        var family = families.saveAndFlush(new VoucherFamily(
                companyId, storeId, "001", sequence, createdAt));
        return new Voucher(
                family, storeId, code, new BigDecimal(amount),
                List.of(originTicket), createdAt, expiresOn);
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

package com.tpverp.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.document.CommercialDocumentType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles({"test", "dev"})
@SpringBootTest
class DevSampleDataSeederPostgreSqlTest {

    private static final String SCHEMA =
            "tpv_erp_dev_seed_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path KEY_DIRECTORY = Path.of(
            "target", "test-installation-keys", UUID.randomUUID().toString());
    private static final Path CERTIFICATE_DIRECTORY = Path.of(
            "target", "test-verifactu-certificates", UUID.randomUUID().toString());

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DevSampleDataSeeder seeder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> databaseUrl()
                + (databaseUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"));
        registry.add("spring.datasource.password", () -> environment("TPV_TEST_DB_PASSWORD", "admin"));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("tpv.installation.key-directory", KEY_DIRECTORY::toString);
        registry.add("tpv.verifactu.secret-directory", CERTIFICATE_DIRECTORY::toString);
        registry.add("tpv.dev.sample-data.enabled", () -> "true");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                databaseUrl(),
                environment("TPV_TEST_DB_USERNAME", "tpv_erp_test"),
                environment("TPV_TEST_DB_PASSWORD", "admin"));
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
        deleteDirectory(KEY_DIRECTORY);
        deleteDirectory(CERTIFICATE_DIRECTORY);
    }

    @Test
    void seedsFrontendDataForEveryDocumentType() {
        var documentTypes = jdbc.queryForList(
                "select tipo from documento where numero is not null", String.class);

        assertThat(documentTypes)
                .containsAll(DevSampleDataSeeder.documentTypes().stream()
                        .map(CommercialDocumentType::name)
                        .toList());
        assertThat(count("producto")).isGreaterThanOrEqualTo(7);
        assertThat(count("cliente")).isGreaterThanOrEqualTo(1);
        assertThat(count("proveedor")).isGreaterThanOrEqualTo(1);
        assertThat(count("salida_almacen")).isGreaterThanOrEqualTo(1);
        assertThat(count("terminal")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void seedsOperationalAdminForTheDemoTerminal() {
        var admin = jdbc.queryForMap("""
                select u.password_hash, u.protegido, u.activo, u.must_change_password,
                       u.tienda_id, r.nombre as rol, r.protegido as rol_protegido
                from usuario u
                join rol r on r.id = u.rol_id
                where u.nombre = 'ADMIN' and u.tienda_id is not null
                """);

        assertThat(passwordEncoder.matches("0000", admin.get("password_hash").toString())).isTrue();
        assertThat(admin)
                .containsEntry("protegido", true)
                .containsEntry("activo", true)
                .containsEntry("must_change_password", false)
                .containsEntry("rol", "ADMIN")
                .containsEntry("rol_protegido", true);
        assertThat(jdbc.queryForObject(
                "select id from terminal where tipo = 'SERVIDOR'", UUID.class))
                .isEqualTo(DevSampleDataSeeder.terminalId());
    }

    @Test
    void seedsProductsForTheSupportedDemoPricingScenarios() {
        assertProductPricing("DEV-CAFE-SOCIO", "MEMBER_PRICE", "MEMBER_PRICE",
                "9.90", "7.50", null, null);
        assertProductPricing("DEV-ZUMO-OFERTA", "DISCOUNT_PRICE", "OFFER_PRICE",
                "2.80", null, "2.10", null);
        assertProductPricing("DEV-GALLETAS-PROMO", "DISCOUNT_PRICE", "OFFER_DISCOUNT",
                "3.00", null, "2.40", "20.00");
        assertProductPricing("DEV-PAN-SIN-DESCUENTO", "NONE", "NORMAL",
                "1.20", null, null, null);

        assertThat(price("DEV-LECHE-MAYOR", "MAYORISTA"))
                .isEqualByComparingTo("1.05");
    }

    @Test
    void restoresDevelopmentTerminalCredentialWhenReseeding() {
        jdbc.update(
                "update terminal set credential_hash = ? where id = ?",
                passwordEncoder.encode("stale-provisioned-credential"),
                DevSampleDataSeeder.terminalId());

        seeder.seed();

        var credentialHash = jdbc.queryForObject(
                "select credential_hash from terminal where id = ?",
                String.class,
                DevSampleDataSeeder.terminalId());
        assertThat(passwordEncoder.matches("DEV-SERVER", credentialHash)).isTrue();
    }

    @Test
    void seedsBronzeSilverAndGoldMembersIdempotently() {
        var members = jdbc.queryForList("""
                select concat(c.nombre_fiscal, '|', mc.code, '|',
                              to_char(mc.discount_percent, 'FM999990.00'))
                from cliente c
                join miembro m on m.cliente_id = c.id and m.active = true
                join member_category mc on mc.id = m.member_category_id
                where c.nombre_fiscal in (
                    'CLIENTE BRONCE DEMO', 'CLIENTE PLATA DEMO', 'CLIENTE ORO DEMO')
                order by mc.discount_percent
                """, String.class);

        assertThat(members).containsExactly(
                "CLIENTE BRONCE DEMO|BRONCE|5.00",
                "CLIENTE PLATA DEMO|PLATA|10.00",
                "CLIENTE ORO DEMO|ORO|15.00");

        seeder.seed();

        assertThat(jdbc.queryForObject("""
                select count(*)
                from miembro m
                join cliente c on c.id = m.cliente_id
                where c.nombre_fiscal in (
                    'CLIENTE BRONCE DEMO', 'CLIENTE PLATA DEMO', 'CLIENTE ORO DEMO')
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void seedsSalesRoleWithoutWarehouseManagementPermission() {
        var permissions = jdbc.queryForList("""
                select permiso.codigo
                from rol
                join rol_permiso on rol_permiso.rol_id = rol.id
                join permiso on permiso.id = rol_permiso.permiso_id
                where rol.nombre = 'VENTAS'
                """, String.class);

        assertThat(permissions).contains(
                "CUSTOMERS_READ",
                "CUSTOMERS_WRITE",
                "SUPPLIERS_READ",
                "SUPPLIERS_WRITE")
                .doesNotContain("GESTION_ALMACEN");
    }

    @Test
    void seedsAboutOneThousandFrontendDocumentsWithDifferentDates() {
        assertThat(count("documento")).isGreaterThanOrEqualTo(1_000);
        assertThat(jdbc.queryForObject("select count(distinct fecha) from documento", Integer.class))
                .isGreaterThanOrEqualTo(90);
    }

    @Test
    void synchronizesDocumentCountersWithSeededDocumentNumbersIdempotently() {
        var countersBefore = jdbc.queryForList("""
                select tipo || '|' || periodo || '|' || ultimo_numero
                from contador_documento
                where tienda_id = (select id from tienda where codigo_tienda = '001')
                  and tipo in ('AV', 'AC', 'FV', 'FC', 'FRV', 'FRC')
                order by tipo, periodo
                """, String.class);

        assertThat(countersBefore).contains(
                "AV|2026|144",
                "FV|2026|144");

        seeder.seed();

        assertThat(jdbc.queryForList("""
                select tipo || '|' || periodo || '|' || ultimo_numero
                from contador_documento
                where tienda_id = (select id from tienda where codigo_tienda = '001')
                  and tipo in ('AV', 'AC', 'FV', 'FC', 'FRV', 'FRC')
                order by tipo, periodo
                """, String.class)).isEqualTo(countersBefore);
    }

    @Test
    void seedsOneClosedZeroBalanceCashHistoryIdempotently() {
        var historyId = DevSampleDataSeeder.cashSessionHistoryId();
        var history = jdbc.queryForMap("""
                select sc.estado, sc.fondo_inicial, sc.efectivo_teorico,
                       sc.fondo_dejado, sc.descuadre,
                       t.nombre as terminal_nombre, u.user_id as usuario_apertura
                from sesion_caja sc
                join terminal t on t.id = sc.terminal_id
                join usuario u on u.id = sc.usuario_apertura_id
                where sc.id = ?
                """, historyId);

        assertThat(history)
                .containsEntry("estado", "CERRADA")
                .containsEntry("terminal_nombre", "SERVIDOR PRUEBAS")
                .containsEntry("usuario_apertura", "E-999001");
        assertThat(history.get("fondo_inicial").toString()).isEqualTo("0.00");
        assertThat(history.get("efectivo_teorico").toString()).isEqualTo("0.00");
        assertThat(history.get("fondo_dejado").toString()).isEqualTo("0.00");
        assertThat(history.get("descuadre").toString()).isEqualTo("0.00");
        assertThat(jdbc.queryForObject(
                "select count(*) from sesion_caja where estado = 'ABIERTA'", Integer.class))
                .isEqualTo(0);

        seeder.seed();

        assertThat(jdbc.queryForObject(
                "select count(*) from sesion_caja where id = ?", Integer.class, historyId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from sesion_caja where estado = 'ABIERTA'", Integer.class))
                .isEqualTo(0);
    }

    private Integer count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void assertProductPricing(
            String code,
            String discountType,
            String priceUseMode,
            String salePrice,
            String memberPrice,
            String offerPrice,
            String offerDiscountPercent) {
        var product = jdbc.queryForMap("""
                select p.discount_type, p.price_use_mode, p.oferta_activa,
                       p.oferta_desde, p.oferta_hasta, p.oferta_descuento_porcentaje
                from producto p
                join producto_identificador pi on pi.producto_id = p.id
                where pi.tipo = 'CODIGO' and pi.valor = ?
                """, code);

        assertThat(product)
                .containsEntry("discount_type", discountType)
                .containsEntry("price_use_mode", priceUseMode);
        assertThat(price(code, "VENTA")).isEqualByComparingTo(salePrice);
        assertOptionalPrice(code, "MEMBER", memberPrice);
        assertOptionalPrice(code, "OFERTA", offerPrice);
        if (offerPrice == null) {
            assertThat(product).containsEntry("oferta_activa", false);
        } else {
            assertThat(product.get("oferta_activa")).isEqualTo(true);
            assertThat(product.get("oferta_desde")).isNotNull();
            assertThat(product.get("oferta_hasta")).isNotNull();
        }
        if (offerDiscountPercent == null) {
            assertThat(product.get("oferta_descuento_porcentaje")).isNull();
        } else {
            assertThat((BigDecimal) product.get("oferta_descuento_porcentaje"))
                    .isEqualByComparingTo(offerDiscountPercent);
        }
    }

    private void assertOptionalPrice(String code, String tier, String expected) {
        var amount = price(code, tier);
        if (expected == null) {
            assertThat(amount).isNull();
        } else {
            assertThat(amount).isEqualByComparingTo(expected);
        }
    }

    private BigDecimal price(String code, String tier) {
        return jdbc.query("""
                select pp.importe
                from producto_precio pp
                join producto_identificador pi on pi.producto_id = pp.producto_id
                where pi.tipo = 'CODIGO' and pi.valor = ? and pp.tarifa = ?
                """, result -> result.next() ? result.getBigDecimal(1) : null, code, tier);
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String databaseUrl() {
        return environment("TPV_TEST_DB_URL", "jdbc:postgresql://localhost:5432/tpv_erp_test");
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

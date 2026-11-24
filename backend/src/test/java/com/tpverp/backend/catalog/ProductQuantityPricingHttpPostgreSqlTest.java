package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.shared.access.OperationalMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Uses the application's MVC JSON converter and a dedicated migrated PostgreSQL schema. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class ProductQuantityPricingHttpPostgreSqlTest {
    private static final String URL = System.getenv("TPV_ERP_TEST_DB_URL");
    private static final String USER = System.getenv("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "product_pricing_http_" + UUID.randomUUID().toString().replace("-", "");
    private static final Path KEY_DIRECTORY = Path.of("target", "test-installation-keys", UUID.randomUUID().toString());
    private static final Path CERTIFICATE_DIRECTORY = Path.of("target", "test-verifactu-certificates", UUID.randomUUID().toString());
    private static final String EXACT_PRICE = "99999999999999999.999";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private AuditService audit;
    @MockitoBean private InstallationStatusService installationStatus;

    private UUID familyId;
    private UUID taxId;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("tpv.installation.key-directory", KEY_DIRECTORY::toString);
        registry.add("tpv.verifactu.secret-directory", CERTIFICATE_DIRECTORY::toString);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (URL == null || USER == null || PASSWORD == null) return;
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
        deleteDirectory(KEY_DIRECTORY);
        deleteDirectory(CERTIFICATE_DIRECTORY);
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @BeforeEach
    void prepare() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        familyId = UUID.randomUUID();
        taxId = UUID.randomUUID();
        String address = "{\"linea1\":\"Test\",\"ciudad\":\"Las Palmas\",\"codigoPostal\":\"35001\",\"provincia\":\"Las Palmas\",\"pais\":\"ES\"}";
        jdbc.update("insert into empresa (id,tax_id,razon_social,domicilio_fiscal) values (?,?,'HTTP test',cast(? as jsonb))",
                companyId, "B" + companyId.toString().replace("-", "").substring(0, 8), address);
        jdbc.update("insert into tienda (id,empresa_id,codigo_tienda,nombre,direccion,address_normalized_hash,timezone,moneda,locale) values (?,?,'401','HTTP test',cast(? as jsonb),?,'Atlantic/Canary','EUR','es-ES')",
                storeId, companyId, address, "product-pricing-" + storeId);
        jdbc.update("insert into familia (id,tienda_id,family_id,family_code,nombre,predeterminada) values (?,?,'GENERAL','000','GENERAL',true)",
                familyId, storeId);
        jdbc.update("insert into impuesto_tienda (id,tienda_id,porcentaje) values (?,?,0)", taxId, storeId);
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(installationStatus.status()).thenReturn(new InstallationStatusService.InstallationStatus(
                UUID.randomUUID(), "HTTP-TEST", java.time.Instant.now(), null,
                OperationalMode.DEVELOPMENT, null, true));
    }

    @Test
    void savesAndReopensEnabledAndDisabledThresholdsAndDistinguishesOmissionFromClear() throws Exception {
        JsonNode created = sendPost(payload("{\"mode\":\"UNIT_THRESHOLD\",\"enabled\":true,\"tiers\":[{\"quantity\":2,\"price\":\""
                + EXACT_PRICE + "\"},{\"quantity\":5,\"price\":\"1.235\"}]}"));
        UUID productId = UUID.fromString(created.get("id").textValue());
        assertPricing(created, true);
        assertPricing(reopen(productId), true);
        assertThat(created.get("priceUseMode").textValue()).isEqualTo("MEMBER_PRICE");
        assertThat(created.get("salePrice").decimalValue()).isEqualByComparingTo("0.78");
        assertThat(created.get("memberPrice").decimalValue()).isEqualByComparingTo("0.624");

        assertPricing(sendPut(productId, payload("{\"mode\":\"UNIT_THRESHOLD\",\"enabled\":false,\"tiers\":[{\"quantity\":2,\"price\":\""
                + EXACT_PRICE + "\"},{\"quantity\":5,\"price\":\"1.235\"}]}")), false);
        assertPricing(reopen(productId), false);

        assertPricing(sendPut(productId, payload(null)), false);
        assertPricing(reopen(productId), false);

        assertThat(sendPut(productId, payload("null")).get("quantityPricing").isNull()).isTrue();
        assertThat(reopen(productId).get("quantityPricing").isNull()).isTrue();
    }

    private JsonNode sendPost(String body) throws Exception {
        var result = mvc.perform(post("/api/v1/products")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        if (result.getResponse().getStatus() != 200 && result.getResolvedException() != null) {
            throw new AssertionError("Product POST failed during MVC request conversion", result.getResolvedException());
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode sendPut(UUID id, String body) throws Exception {
        return JSON.readTree(mvc.perform(put("/api/v1/products/{id}", id)
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode reopen(UUID id) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/products/management/{id}", id)
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String payload(String quantityPricing) {
        String base = """
                {"familyId":"%s","taxId":"%s","productType":"UNIT","discountType":"MEMBER_PRICE",
                 "priceUseMode":"MEMBER_PRICE","name":"Cafe HTTP","purchasePrice":1.20,"salePrice":0.78,
                 "memberPrice":0.624,
                 "taxesIncluded":true,"offerActive":false,"code":"HTTP-001"%s}
                """;
        return base.formatted(familyId, taxId,
                quantityPricing == null ? "" : ",\"quantityPricing\":" + quantityPricing);
    }

    private static void assertPricing(JsonNode response, boolean enabled) {
        JsonNode pricing = response.get("quantityPricing");
        assertThat(pricing.get("mode").textValue()).isEqualTo("UNIT_THRESHOLD");
        assertThat(pricing.get("enabled").booleanValue()).isEqualTo(enabled);
        assertThat(pricing.get("tiers").size()).isEqualTo(2);
        assertThat(pricing.at("/tiers/0/price").textValue()).isEqualTo(EXACT_PRICE);
        assertThat(pricing.at("/tiers/1/price").textValue()).isEqualTo("1.235");
    }
}

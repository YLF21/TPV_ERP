package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.tpverp.saas.SaasTestData.fiscalAddress;
import static com.tpverp.saas.SaasTestData.validCif;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AdminApiPostgresIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void creaEmpresaConPostgresReal() throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCompanyRequest(
                                "Empresa Postgres",
                                validCif("B70707070"),
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                                fiscalAddress(),
                                "001",
                                "Tienda 1",
                                fiscalAddress(),
                                "Atlantic/Canary",
                                Instant.parse("2099-07-01T00:00:00Z"),
                                2,
                                1))))
                .andExpect(status().isOk())
                .andReturn();

        CreateCompanyResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                CreateCompanyResponse.class);
        assertThat(response.licenseReference()).isEqualTo(
                "LIC-" + validCif("B70707070") + "-001");
    }

    @Test
    void triggerImpideColisionConcurrenteEntreRealms() throws Exception {
        var companyResult = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCompanyRequest(
                                "Empresa concurrencia usuarios",
                                validCif("B70707180"),
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                                fiscalAddress(),
                                "001",
                                "Tienda 1",
                                fiscalAddress(),
                                "Atlantic/Canary",
                                Instant.parse("2099-07-01T00:00:00Z"),
                                2,
                                1))))
                .andExpect(status().isOk())
                .andReturn();
        UUID companyId = mapper.readValue(
                companyResult.getResponse().getContentAsString(),
                CreateCompanyResponse.class).companyId();
        String username = "race-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> adminCreated = executor.submit(() -> insertConcurrentUser(
                    ready,
                    start,
                    "insert into saas_admin_user(id, username, password_hash, active, created_at) "
                            + "values (?, ?, ?, true, current_timestamp)",
                    UUID.randomUUID(),
                    username,
                    "a".repeat(64)));
            Future<Boolean> tenantCreated = executor.submit(() -> insertConcurrentUser(
                    ready,
                    start,
                    "insert into saas_tenant_user(id, company_id, username, password_hash, role_name, active, created_at) "
                            + "values (?, ?, ?, ?, 'VIEWER', true, current_timestamp)",
                    UUID.randomUUID(),
                    companyId,
                    username.toUpperCase(Locale.ROOT),
                    "b".repeat(64)));
            ready.await();
            start.countDown();

            assertThat(java.util.List.of(adminCreated.get(), tenantCreated.get()))
                    .containsExactlyInAnyOrder(true, false);
        }

        Integer registered = jdbc.queryForObject(
                "select count(*) from saas_global_username where normalized_username = ?",
                Integer.class,
                username.toLowerCase(Locale.ROOT));
        assertThat(registered).isEqualTo(1);
    }

    private boolean insertConcurrentUser(
            CountDownLatch ready,
            CountDownLatch start,
            String sql,
            Object... arguments) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> {
                jdbc.update(sql, arguments);
                return true;
            }));
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}

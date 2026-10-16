package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Defaults to a disposable Testcontainers database; an explicit TPV_TEST_DB_URL must be test-only. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DemoRequestPostgreSqlTest {
    private static final String SCHEMA = "marketing_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DemoRequestRepository repository;
    @Autowired DemoRequestService service;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void verifyIsolation() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @Test
    void realPublicControllerPersistsOneLeadAndRetriesReturnExactlyItsReceipt() throws Exception {
        UUID key = UUID.randomUUID();
        String payload = payload("http-" + key, null);
        mvc.perform(get("/api/v1/public/demo-requests/availability"))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":true}"));
        String first = mvc.perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content(payload))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        JsonNode receipt = mapper.readTree(first);
        UUID id = UUID.fromString(receipt.get("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from saas_marketing_demo_request where id = ?",
                Long.class, id)).isEqualTo(1L);
        mvc.perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content(payload))
                .andExpect(status().isCreated()).andExpect(content().json(first));
        mvc.perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key).content(payload.replace("Synthetic test", "Changed synthetic test")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from saas_marketing_demo_request where idempotency_key = ?",
                Long.class, key)).isEqualTo(1L);
        assertThat(service.page(0, 20, null, id.toString()).items()).singleElement()
                .satisfies(row -> assertThat(row.id()).isEqualTo(id));
        // A more recent lead mentioning this reference must not intercept exact refreshes.
        repository.save(lead(id.toString(), "Mentions receipt " + id, Instant.now().plusSeconds(60), "NEW"));
        DemoRequestPage byReference = service.page(0, 1, null, id.toString());
        assertThat(byReference.total()).isEqualTo(1);
        assertThat(byReference.items()).singleElement().satisfies(row -> assertThat(row.id()).isEqualTo(id));
        assertThat(service.page(0, 1, DemoRequestStatus.CLOSED, id.toString()).items()).isEmpty();
        assertThat(service.page(0, 1, null, id.toString().toUpperCase(java.util.Locale.ROOT)).items())
                .singleElement().satisfies(row -> assertThat(row.id()).isEqualTo(id));
        mvc.perform(get("/api/v1/admin/demo-requests/page")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentSubmissionsAndAnApplicationRestartReuseThePersistentKey() throws Exception {
        UUID key = UUID.randomUUID();
        CreateDemoRequest request = request("concurrent-" + key);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<DemoRequestReceipt> receipts = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<java.util.concurrent.Future<DemoRequestReceipt>>();
            for (int i = 0; i < 4; i++) calls.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return service.submit(request, key, "test-concurrent-ip");
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var call : calls) receipts.add(call.get(20, TimeUnit.SECONDS));
        }
        assertThat(receipts).hasSize(4).allMatch(receipt -> receipt.equals(receipts.getFirst()));
        assertThat(jdbc.queryForObject("select count(*) from saas_marketing_demo_request where idempotency_key = ?",
                Long.class, key)).isEqualTo(1L);
        // A fresh service/limiter has no in-memory history: the receipt must still come from PostgreSQL.
        var restarted = new DemoRequestService(repository, java.time.Clock.systemUTC(),
                org.mockito.Mockito.mock(com.tpverp.saas.admin.AdminAuditService.class), 120);
        assertThat(restarted.submit(request, key, "test-concurrent-ip")).isEqualTo(receipts.getFirst());
    }

    @Test
    void pagesReachOlderLeadsAndSearchTreatsWildcardsAndSqlAsLiteralText() throws Exception {
        String tag = "inbox-" + UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-15T00:00:00Z");
        for (int i = 0; i < 125; i++) {
            repository.save(lead(tag, "Company " + tag, createdAt.plusSeconds(i), i < 5 ? "CONTACTED" : "NEW"));
        }
        DemoRequestPage first = service.page(0, 20, null, tag);
        DemoRequestPage last = service.page(6, 20, null, tag);
        assertThat(first.total()).isEqualTo(125);
        assertThat(first.items()).hasSize(20);
        assertThat(last.items()).hasSize(5);
        assertThat(first.items()).extracting(DemoRequestView::id)
                .doesNotContainAnyElementsOf(last.items().stream().map(DemoRequestView::id).toList());
        assertThat(service.page(0, 100, DemoRequestStatus.NEW, tag).total()).isEqualTo(120);
        repository.save(lead(tag, "Literal %_! " + tag, createdAt, "NEW"));
        assertThat(service.page(0, 20, null, "%_!").items()).singleElement()
                .satisfies(row -> assertThat(row.company()).contains("Literal %_!"));
        assertThat(service.page(0, 20, null, "' OR true --").total()).isZero();
        assertThat(service.latest()).hasSize(100);
        mvc.perform(get("/api/v1/admin/demo-requests/page").header("Authorization", basic("viewer"))
                        .param("page", "6").param("size", "20").param("query", tag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(126))
                .andExpect(jsonPath("$.items.length()").value(6));
    }

    @Test
    void onlyAuthorizedOperatorChangesStateAndTheChangeIsAudited() throws Exception {
        DemoRequestReceipt receipt = service.submit(request("status-" + UUID.randomUUID()));
        String route = "/api/v1/admin/demo-requests/" + receipt.id() + "/status";
        mvc.perform(put(route).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\",\"expectedStatus\":\"NEW\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(route).header("Authorization", basic("viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\",\"expectedStatus\":\"NEW\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(route).header("Authorization", basic("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\",\"expectedStatus\":\"NEW\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONTACTED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(jdbc.queryForObject("select status from saas_marketing_demo_request where id = ?",
                String.class, receipt.id())).isEqualTo("CONTACTED");
        assertThat(jdbc.queryForObject("""
                select count(*) from saas_admin_audit_log
                where action = 'UPDATE_DEMO_REQUEST_STATUS' and target_id = ? and username = 'admin'
                """, Long.class, receipt.id().toString())).isEqualTo(1L);
        mvc.perform(put("/api/v1/admin/demo-requests/" + UUID.randomUUID() + "/status")
                        .header("Authorization", basic("admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"expectedStatus\":\"NEW\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(put(route).header("Authorization", basic("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"expectedStatus\":\"NEW\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATUS_CONFLICT"));
        // Retrying the already successful destination is idempotent, with no duplicate audit.
        mvc.perform(put(route).header("Authorization", basic("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\",\"expectedStatus\":\"NEW\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONTACTED"));
        assertThat(jdbc.queryForObject("""
                select count(*) from saas_admin_audit_log
                where action = 'UPDATE_DEMO_REQUEST_STATUS' and target_id = ?
                """, Long.class, receipt.id().toString())).isEqualTo(1L);
    }

    @Test
    void honeypotAndInvalidInputNeverReachTheLeadInbox() throws Exception {
        String marker = "discard-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(marker, "https://synthetic-bot.invalid")))
                .andExpect(status().isCreated());
        assertThat(service.page(0, 20, null, marker).total()).isZero();
        mvc.perform(post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(marker, null).replace("Synthetic test", " ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").value("REQUIRED"));
        assertThat(service.page(0, 20, null, marker).total()).isZero();
    }

    private static String basic(String username) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":admin").getBytes(StandardCharsets.UTF_8));
    }

    private String payload(String marker, String website) throws Exception {
        return mapper.writeValueAsString(new CreateDemoRequest(null, List.of(DemoProduct.APP_VENTA, DemoProduct.APP_SAAS),
                "Synthetic test", marker, marker + "@example.com", null, "Isolated integration test",
                "es", true, website, null, null, null, null, null));
    }

    private static CreateDemoRequest request(String marker) {
        return new CreateDemoRequest(DemoProduct.APP_VENTA, null, "Synthetic test", marker, marker + "@example.com",
                null, null, "es", true, "", null, null, null, null, null);
    }

    private static DemoRequestView lead(String name, String company, Instant createdAt, String status) {
        return new DemoRequestView(UUID.randomUUID(), DemoProduct.APP_VENTA, List.of(DemoProduct.APP_VENTA),
                name, company, "synthetic@example.com", null, null, "es", null, null, null, null, null,
                status, createdAt, createdAt);
    }
}

package com.tpverp.saas.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import com.tpverp.saas.admin.IntegrationSecretCipher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"spring.flyway.default-schema=client_access_request_test",
        "spring.datasource.hikari.schema=client_access_request_test",
        "spring.jpa.properties.hibernate.default_schema=client_access_request_test"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ClientAccessRequestsPostgreSqlTest {
    private static final String PUBLIC = "/api/v1/public/client-access-requests";
    private static final String REQUESTS = "/api/v1/admin/client-access-requests";
    private static final String USERS = "/api/v1/admin/tenant-users";
    private static final String PASSWORD = "Mi-contraseña-12";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired IntegrationSecretCipher cipher;
    private String staff;

    @BeforeEach
    void staffSession() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("client_access_request_test");
        staff = "Bearer " + sessions.issue("admin", "viewer").token();
    }

    @Test
    void pendingCannotLoginAndApprovalWithoutStoresCreatesOnlyTheCustomerIdentity() throws Exception {
        var form = form();
        String username = form.get("username").toString();
        UUID id = submit(form);
        form.put("fullName", "Retry changed the body");
        postJson(PUBLIC, form, null).andExpect(status().isAccepted()).andExpect(content().string(""));
        assertThat(jdbc.queryForObject("select password_hash from saas_client_access_request where id=?", String.class, id))
                .startsWith("$2").doesNotContain(PASSWORD);
        login(username).andExpect(status().isUnauthorized());
        mvc.perform(get(USERS).header("Authorization", staff).param("q", username))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(REQUESTS).header("Authorization", staff).param("q", username).param("status", "PENDING"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].fullName").value("Nombre cliente"))
                .andExpect(jsonPath("$.items[0].taxId").doesNotExist());
        mvc.perform(get(REQUESTS + "/" + id).header("Authorization", staff))
                .andExpect(jsonPath("$.requestedStores[0]").value("Mi tienda solicitada"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());

        postJson(REQUESTS + "/" + id + "/approve", Map.of("version", 0, "grants", List.of()), staff)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.tenantUserId").isNotEmpty())
                .andExpect(jsonPath("$.reviewedBy").value("viewer"))
                .andExpect(jsonPath("$.delivery.status").value("PENDING")).andExpect(jsonPath("$.delivery.attempts").value(0));
        assertThat(jdbc.queryForObject("select password_hash is null from saas_client_access_request where id=?", Boolean.class, id)).isTrue();
        String token = "Bearer " + json.readTree(login(username).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("tenant")).andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn().getResponse().getContentAsByteArray()).get("accessToken").asText();
        mvc.perform(get("/api/v1/tenant/access").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.companies.length()").value(0));
        mvc.perform(get("/api/v1/tenant/me").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get(USERS).header("Authorization", staff).param("q", username).param("sort", "storeCount"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].storeCount").value(0))
                .andExpect(jsonPath("$.items[0].companyCount").value(0));
        for (String path : List.of(REQUESTS, REQUESTS + "/" + id, USERS)) {
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().isUnauthorized());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        postJson(REQUESTS + "/" + id + "/approve", Map.of("version", 1, "grants", List.of()), token)
                .andExpect(status().isUnauthorized());
        postJson(REQUESTS + "/" + id + "/reject", Map.of("version", 0, "reason", "Conflicto"), staff)
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("""
                select details from saas_admin_audit_log where target_id=? and action='APPROVE_CLIENT_ACCESS_REQUEST'
                and username='viewer' and origin='SAAS_ADMIN'
                """, String.class, id.toString())).startsWith("tenantUserId=").doesNotContain(PASSWORD, "$2", "@");
        postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", PASSWORD, "newPassword", "Cambio-cliente-01"), token)
                .andExpect(status().isNoContent());
        postJson("/api/v1/auth/password/recovery/request", Map.of("username", username), null)
                .andExpect(status().isAccepted());
        String resetToken = cipher.decrypt(jdbc.queryForObject("""
                select encrypted_payload from saas_security_notification_outbox
                where realm='tenant' and username_key=? and event_type='PASSWORD_RESET_REQUESTED'
                """, String.class, username));
        postJson("/api/v1/auth/password/recovery/confirm",
                Map.of("token", resetToken, "newPassword", "Recuperada-cliente-01"), null).andExpect(status().isNoContent());
        postJson("/api/v1/auth/tenant/login",
                Map.of("username", username, "password", "Recuperada-cliente-01"), null).andExpect(status().isOk());
    }

    @Test
    void onlyStaffSelectedStoresAreGrantedAndAnyFailureRollsBackTheWholeDecision() throws Exception {
        var form = form();
        String username = form.get("username").toString();
        UUID id = submit(form);
        UUID first = company(jdbc), second = company(jdbc);
        UUID allowed = store(jdbc, first, "001"), hidden = store(jdbc, first, "002"), cross = store(jdbc, second, "001");
        var firstGrant = Map.of("companyId", first, "storeIds", List.of(allowed));
        var wrongGrant = Map.of("companyId", second, "storeIds", List.of(hidden));
        String action = REQUESTS + "/" + id + "/approve";
        postJson(action, Map.of("version", 0, "grants", List.of(firstGrant, wrongGrant)), staff).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from saas_client_decision_email where request_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from saas_global_username where normalized_username=?",
                Integer.class, username)).isZero();
        postJson(action, Map.of("version", 0, "grants", List.of(firstGrant, Map.of("companyId", second, "storeIds", List.of(cross)))), staff)
                .andExpect(status().isOk());
        var stores = jdbc.queryForList("""
                select a.store_id from saas_tenant_store_access a join saas_tenant_user u on u.id=a.user_id where u.username=?
                """, UUID.class, username);
        assertThat(stores).containsExactlyInAnyOrder(allowed, cross);
        assertThat(jdbc.queryForObject("""
                select bool_and(cardinality(a.company_privileges)=0 and a.role_name='VIEWER') from saas_tenant_company_access a
                join saas_tenant_user u on u.id=a.user_id where u.username=?
                """, Boolean.class, username)).isTrue();
    }

    @Test
    void collidingUsernameCanBeRejectedAndPublicRetryCannotReopenIt() throws Exception {
        var form = form();
        form.put("username", "ViEwEr");
        UUID id = submit(form);
        postJson(REQUESTS + "/" + id + "/approve", Map.of("version", 0, "grants", List.of()), staff).andExpect(status().isConflict());
        postJson(REQUESTS + "/" + id + "/reject", Map.of("version", 1, "reason", "Duplicado"), staff).andExpect(status().isConflict());
        postJson(REQUESTS + "/" + id + "/reject", Map.of("version", 0, "reason", " "), staff).andExpect(status().isBadRequest());
        String privateNote = "Private-review-note".repeat(30);
        var invalid = postJson(REQUESTS + "/" + id + "/reject", Map.of("version", 0, "reason", privateNote), staff)
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(invalid.getContentAsString()).doesNotContain(privateNote);
        postJson(REQUESTS + "/" + id + "/reject", Map.of("version", 0, "reason", "Duplicado"), staff)
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewReason").value("Duplicado"));
        postJson(PUBLIC, form, null).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("""
                select status='REJECTED' and password_hash is null and tenant_user_id is null
                from saas_client_access_request where id=?
                """, Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select realm from saas_global_username where normalized_username='viewer'", String.class))
                .isEqualTo("admin");
        login("ViEwEr").andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentApproveAndRejectCommitExactlyOneDecision() throws Exception {
        var form = form();
        UUID id = submit(form);
        String url = REQUESTS + "/" + id;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approval = pool.submit(() -> {
                start.await();
                return postJson(url + "/approve", Map.of("version", 0, "grants", List.of()), staff)
                        .andReturn().getResponse().getStatus();
            });
            var rejection = pool.submit(() -> {
                start.await();
                return postJson(url + "/reject", Map.of("version", 0, "reason", "Revisado"), staff)
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(approval.get(15, TimeUnit.SECONDS), rejection.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_audit_log where target_type='CLIENT_ACCESS_REQUEST' and target_id=?",
                Integer.class, id.toString())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from saas_client_decision_email where request_id=?", Integer.class, id)).isOne();
    }

    @Test
    void concurrentRequestsForTheSameUsernameCannotReplaceEachOthersIdentityOrGrants() throws Exception {
        var first = form();
        var second = form();
        String username = first.get("username").toString();
        second.put("username", username.toUpperCase(java.util.Locale.ROOT));
        second.put("password", "Clave-segunda-42");
        UUID firstId = submit(first), secondId = submit(second);
        UUID company = company(jdbc), firstStore = store(jdbc, company, "001"), secondStore = store(jdbc, company, "002");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (var pair : List.of(Map.entry(firstId, firstStore), Map.entry(secondId, secondStore))) {
                results.add(pool.submit(() -> {
                    start.await();
                    return postJson(REQUESTS + "/" + pair.getKey() + "/approve",
                            Map.of("version", 0, "grants", List.of(Map.of("companyId", company, "storeIds", List.of(pair.getValue())))), staff)
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            assertThat(List.of(results.getFirst().get(15, TimeUnit.SECONDS), results.getLast().get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        UUID winner = jdbc.queryForObject("select id from saas_client_access_request where lower(username)=? and status='APPROVED'",
                UUID.class, username);
        assertThat(jdbc.queryForList("""
                select a.store_id from saas_tenant_store_access a join saas_tenant_user u on u.id=a.user_id where lower(u.username)=?
                """, UUID.class, username)).containsExactly(winner.equals(firstId) ? firstStore : secondStore);
        login(username).andExpect(status().is(winner.equals(firstId) ? 200 : 401));
    }

    @Test
    void validationAndRateLimitsReturnControlledPublicErrors() throws Exception {
        var form = form();
        for (String password : List.of("🔑".repeat(8), "界".repeat(25))) {
            form.put("password", password);
            var response = postJson(PUBLIC, form, null).andExpect(status().isBadRequest()).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain(password);
        }
        form.put("password", PASSWORD);
        form.put("requestedStores", List.of());
        for (int attempt = 0; attempt < 5; attempt++) {
            form.put("submissionId", UUID.randomUUID());
            postJson(PUBLIC, form, null).andExpect(status().isAccepted());
        }
        form.put("submissionId", UUID.randomUUID());
        postJson(PUBLIC, form, null).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void migrateExistingGrantAndCredentialFromV86() throws Exception {
        String schema = "client_access_v87_" + UUID.randomUUID().toString().replace("-", "");
        var migration = Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(dataSource).locations("classpath:db/migration").defaultSchema(schema).schemas(schema);
        try (var connection = dataSource.getConnection()) {
            migration.target("86").load().migrate();
            connection.setSchema(schema);
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            UUID company = company(db), store = store(db, company, "001"), user = UUID.randomUUID();
            db.update("""
                    insert into saas_tenant_user(id,company_id,username,password_hash,role_name,active,created_at)
                    values(?,?,'legacy','synthetic-hash','MANAGER',true,now())
                    """, user, company);
            db.update("""
                    insert into saas_tenant_company_access(user_id,company_id,role_name,company_privileges)
                    values(?,?,'MANAGER',array['READ_BILLING'])
                    """, user, company);
            db.update("insert into saas_tenant_store_access(user_id,company_id,store_id) values(?,?,?)", user, company, store);
            migration.target("87").load().migrate();
            db.update("""
                    insert into saas_client_access_request(id,submission_id,username,full_name,tax_id,email,phone,address,
                    postal_code,city,province,country,requested_stores,status,version,reviewed_at,reviewed_by,review_reason)
                    values(?,?,'old','Client','X','old@example.test','0','a','0','c','p','ES','{}',
                    'REJECTED',1,now(),'staff','old decision')
                    """, UUID.randomUUID(), UUID.randomUUID());
            migration.target("88").load().migrate();
            assertThat(db.queryForObject("select count(*) from saas_client_decision_email", Integer.class)).isZero();
            assertThat(db.queryForObject("""
                    select u.company_id=? and u.password_hash='synthetic-hash' and a.role_name='MANAGER'
                      and a.company_privileges=array['READ_BILLING'] and s.store_id=? from saas_tenant_user u
                    join saas_tenant_company_access a on a.user_id=u.id
                    join saas_tenant_store_access s on s.user_id=u.id and s.company_id=a.company_id where u.id=?
                    """, Boolean.class, company, store, user)).isTrue();
        } finally {
            // Only this test's freshly generated schema, also when using an external test database.
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private Map<String, Object> form() {
        String username = "client-" + UUID.randomUUID();
        var form = new HashMap<String, Object>();
        form.putAll(Map.of("submissionId", UUID.randomUUID(), "username", username, "password", PASSWORD,
                "fullName", "Nombre cliente", "taxId", "prueba", "email", username + "@example.test",
                "phone", "5550100", "address", "Calle Prueba 1", "postalCode", "38001"));
        form.putAll(Map.of("city", "Santa Cruz", "province", "Santa Cruz", "country", "España",
                "requestedStores", List.of("Mi tienda solicitada"), "notes", "Consultar antes de vincular"));
        return form;
    }

    private UUID submit(Map<String, Object> form) throws Exception {
        postJson(PUBLIC, form, null).andExpect(status().isAccepted()).andExpect(content().string(""));
        return jdbc.queryForObject("select id from saas_client_access_request where submission_id=?", UUID.class, form.get("submissionId"));
    }

    private ResultActions login(String username) throws Exception {
        return postJson("/api/v1/auth/tenant/login", Map.of("username", username, "password", PASSWORD), null);
    }

    private ResultActions postJson(String url, Object body, String authorization) throws Exception {
        var request = post(url).contentType("application/json").content(json.writeValueAsBytes(body));
        if (authorization != null) request.header("Authorization", authorization);
        return mvc.perform(request);
    }

    private UUID company(JdbcTemplate db) {
        UUID id = UUID.randomUUID();
        db.update("insert into saas_company(id,name,tax_id,taxpayer_type,tax_regime,created_at) values(?,'Solicitud',?,'SOCIEDAD','IVA',now())",
                id, id.toString().substring(0, 12));
        return id;
    }

    private UUID store(JdbcTemplate db, UUID company, String code) {
        UUID id = UUID.randomUUID();
        db.update("""
                insert into saas_store(id,company_id,code,name,tax_regime,commercial_profile,created_at)
                values(?,?,?,'Tienda','IVA','MINORISTA',now())
                """, id, company, code);
        return id;
    }
}

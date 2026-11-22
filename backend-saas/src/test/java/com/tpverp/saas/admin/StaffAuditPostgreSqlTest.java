package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.flyway.default-schema=staff_audit_test",
        "spring.datasource.hikari.schema=staff_audit_test", "spring.jpa.properties.hibernate.default_schema=staff_audit_test"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StaffAuditPostgreSqlTest {
    private static final String AUDIT = "/api/v1/admin/audit";
    private static final String USERS = "/api/v1/admin/users";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SaasSessionTokenStore sessions;
    @Autowired AdminService service;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void sameActorNameAcrossSourcesIsIsolatedAndFilteredExportIncludesEveryPage() throws Exception {
        String actor = "literal%_" + UUID.randomUUID();
        jdbc.update("""
                insert into saas_admin_audit_log(id,username,origin,action,target_type,target_id,details,created_at)
                select gen_random_uuid(), ?, 'SAAS_ADMIN','UPDATE_STORE','STORE', n::text, '=unique formula',
                       '2026-11-02 23:59:59+00'::timestamptz from generate_series(1,103) n
                """, actor);
        jdbc.update("""
                insert into saas_admin_audit_log(id,username,origin,action,target_type,target_id,created_at)
                values (gen_random_uuid(),?,'OTHER','UPDATE_STORE','STORE','hidden-installation','2026-11-02 12:00+00'),
                       (gen_random_uuid(),?,'SAAS_ADMIN','UPDATE_STORE','STORE','next-day','2026-11-03 00:00+00')
                """, actor, actor);
        String token = "Bearer " + sessions.issue("admin", "viewer").token();
        var page = mvc.perform(get(AUDIT + "/search").header("Authorization", token)
                        .param("from", "2026-11-02").param("to", "2026-11-02").param("action","update_st")
                        .param("username", actor.toUpperCase(java.util.Locale.ROOT)).param("q", "unique").param("page", "1").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(103)).andExpect(jsonPath("$.items.length()").value(3))
                .andReturn().getResponse();
        var ids = jdbc.queryForList("select id::text from saas_admin_audit_log where username=? and details is not null order by created_at desc,id desc offset 100",String.class, actor);
        assertThat(mapper.readTree(page.getContentAsString()).at("/items/0/id").asText()).isEqualTo(ids.getFirst());
        String countEvents = "select count(*) from saas_admin_audit_log where username='viewer' and action='EXPORT_SAAS_AUDIT' and details='rows=103'";
        int previousExports = jdbc.queryForObject(countEvents, Integer.class);
        var response = mvc.perform(get(AUDIT + "/export").header("Authorization", token)
                        .param("username", actor).param("from", "2026-11-02").param("to", "2026-11-02").param("language","zh"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var sheets = workbookEntries(response.getContentAsByteArray());
        assertThat(sheets.get("xl/worksheets/sheet1.xml")).contains("日期和时间", "A104","=unique formula")
                .doesNotContain("hidden-installation","next-day","<f>");
        assertThat(sheets.get("xl/worksheets/sheet2.xml")).contains(actor,"103","UTC");
        assertThat(jdbc.queryForObject(countEvents, Integer.class)).isEqualTo(previousExports + 1);
        for (String path : List.of("/search", "/export")) {
            mvc.perform(get(AUDIT + path).header("Authorization", "Bearer " + sessions.issue("tenant","viewer").token())).andExpect(status().isUnauthorized());
            mvc.perform(get(AUDIT + path).header("Authorization",token).param("from","2026-11-03").param("to","2026-11-02")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void customSortOrdersAllSearchPagesAndExcelBatchesIdentically() throws Exception {
        String actor = "sorted-" + UUID.randomUUID();
        record Sample(String id, String details) {}
        var samples = jdbc.query("""
                insert into saas_admin_audit_log(id,username,origin,action,target_type,target_id,details,created_at)
                select gen_random_uuid(), ?, 'SAAS_ADMIN', 'UPDATE_STORE', 'STORE', n::text,
                       case n % 3 when 0 then 'A' when 1 then 'B' else null end, now()
                from generate_series(1,505) n returning id::text, details
                """, (rs, row) -> new Sample(rs.getString("id"), rs.getString("details")), actor);
        String token = "Bearer " + sessions.issue("admin", "viewer").token();
        Pattern auditId = Pattern.compile("<c r=\"G\\d+\"[^>]*><is><t[^>]*>([0-9a-f-]{36})</t>");

        for (String direction : List.of("ASC", "DESC")) {
            Comparator<String> order = direction.equals("ASC") ? Comparator.naturalOrder() : Comparator.reverseOrder();
            var expected = samples.stream()
                    .sorted(Comparator.comparing(Sample::details, Comparator.nullsLast(order)).thenComparing(Sample::id, order))
                    .map(Sample::id).toList();
            var pagedIds = new ArrayList<String>();
            for (int page = 0; page < 6; page++) {
                var response = mvc.perform(get(AUDIT + "/search").header("Authorization", token)
                                .param("username", actor).param("sortBy", "details").param("sortDirection", direction)
                                .param("page", Integer.toString(page)).param("size", "100"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(expected.size()))
                        .andReturn().getResponse();
                for (var item : mapper.readTree(response.getContentAsByteArray()).path("items")) {
                    pagedIds.add(item.path("id").asText());
                }
            }
            assertThat(pagedIds).containsExactlyElementsOf(expected);

            var response = mvc.perform(get(AUDIT + "/export").header("Authorization", token)
                            .param("username", actor).param("sortBy", "details").param("sortDirection", direction))
                    .andExpect(status().isOk()).andReturn().getResponse();
            String sheet = workbookEntries(response.getContentAsByteArray()).get("xl/worksheets/sheet1.xml");
            assertThat(auditId.matcher(sheet).results().map(match -> match.group(1)).toList())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void auditSearchAndExportRejectInvalidSortParameters() throws Exception {
        String token = "Bearer " + sessions.issue("admin", "viewer").token();
        for (String path : List.of("/search", "/export")) {
            mvc.perform(get(AUDIT + path).header("Authorization", token).param("sortBy", "created_at"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(AUDIT + path).header("Authorization", token).param("sortBy", "id, now()"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(AUDIT + path).header("Authorization", token).param("sortDirection", "DESC NULLS FIRST"))
                    .andExpect(status().isBadRequest());
        }
    }

    private static Map<String, String> workbookEntries(byte[] bytes) throws Exception {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void staffCreationRequiresInternalSessionAndRecordsTheAuthenticatedActor() throws Exception {
        String manager = "Bearer " + sessions.issue("admin", "admin").token();
        String name = "staff-" + UUID.randomUUID();
        var request = Map.of("username", name, "password", "temporary-credential");
        mvc.perform(post(USERS).header("Authorization","Bearer " + sessions.issue("tenant","viewer").token())
                .contentType("application/json").content(mapper.writeValueAsBytes(request))).andExpect(status().isUnauthorized());
        mvc.perform(post(USERS).header("Authorization","Bearer " + sessions.issue("admin","viewer").token()).header("X-Username","forged")
                .contentType("application/json").content(mapper.writeValueAsBytes(request))).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").doesNotExist());
        mvc.perform(post(USERS).header("Authorization",manager)
                .contentType("application/json").content(mapper.writeValueAsBytes(request))).andExpect(status().isConflict());
        assertThat(jdbc.queryForList("select username from saas_admin_audit_log where target_id=? and origin='SAAS_ADMIN' and details is null",
                String.class, name)).containsExactly("viewer");
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_user_role ur join saas_admin_user u on u.id=ur.user_id where u.username=?",
                Integer.class, name)).isZero();
        String actorToken = mapper.readTree(mvc.perform(post("/api/v1/auth/admin/login")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("username",name,"password",request.get("password")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + actorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions", org.hamcrest.Matchers.containsInAnyOrder(AdminPermission.ALL_CODES.toArray())));
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> logout = () -> {
                start.await();
                return mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + actorToken))
                        .andReturn().getResponse().getStatus();
            };
            var first = workers.submit(logout);
            var retry = workers.submit(logout);
            start.countDown();
            assertThat(List.of(first.get(), retry.get())).containsOnly(204);
        }
        assertThat(jdbc.queryForList("select action from saas_admin_audit_log where target_id=? and username=? order by action",
                String.class, name, name)).containsExactly("ADMIN_LOGIN", "ADMIN_LOGOUT");
        String otherSession = sessions.issue("admin", name).token();
        String revokeAll = sessions.issue("admin", name).token();
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization","Bearer "+revokeAll)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization","Bearer "+otherSession)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='ADMIN_LOGOUT_ALL' and username=?", Integer.class, name)).isOne();
        mvc.perform(delete(USERS+"/admin").header("Authorization",manager)).andExpect(status().isConflict());
    }

    @Test
    void pendingPasswordChangePreventsConcurrentOldPasswordLoginAndRefresh() throws Exception {
        String name = "staff-lock-" + UUID.randomUUID();
        String password = "previous-password";
        service.createUser(new CreateAdminUserRequest(name, password));
        String token = sessions.issue("admin", name).token();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var responses = transactions.execute(status -> {
                service.changePassword(name, new ChangeAdminPasswordRequest("replacement-password"));
                var login = workers.submit(() -> mvc.perform(post("/api/v1/auth/admin/login")
                                .contentType("application/json").content(mapper.writeValueAsBytes(new SaasLoginRequest(name, password))))
                        .andReturn().getResponse().getStatus());
                var refresh = workers.submit(() -> mvc.perform(post("/api/v1/auth/refresh")
                        .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
                // Wait for both sessions to encounter the uncommitted credential change before releasing it.
                org.awaitility.Awaitility.await().pollInSameThread().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("""
                                with recursive blocked(pid) as (
                                    select pg_backend_pid()
                                    union
                                    select l.pid from pg_locks l join blocked b on b.pid=any(pg_blocking_pids(l.pid))
                                    where not l.granted
                                ) select count(*)-1 from blocked
                                """, Integer.class) >= 2);
                return List.of(login, refresh);
            });
            for (var response : responses) assertThat(response.get()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_session where realm='admin' and username=?", Integer.class, name)).isZero();
    }

    @Test
    void concurrentDeactivationsPreserveOneActiveStaffAccount() throws Exception {
        var initiallyActive = jdbc.queryForList("select id from saas_admin_user where active", UUID.class);
        jdbc.update("update saas_admin_user set active=(username in ('admin','viewer'))");
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (String target : List.of("admin", "viewer")) {
                results.add(workers.submit(() -> {
                    start.await();
                    try {
                        service.deactivateUser(target);
                        return 200;
                    } catch (org.springframework.web.server.ResponseStatusException rejected) {
                        return rejected.getStatusCode().value();
                    }
                }));
            }
            start.countDown();
            assertThat(List.of(results.getFirst().get(), results.getLast().get())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("select count(*) from saas_admin_user where active", Integer.class)).isOne();
        } finally {
            jdbc.update("update saas_admin_user set active=false where active");
            for (UUID id : initiallyActive) jdbc.update("update saas_admin_user set active=true where id=?", id);
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void soleActiveStaffAccountIsProtectedRegardlessOfHistoricalRole() {
        jdbc.update("update saas_admin_user set active=false where username<>'viewer'");
        assertThatThrownBy(() -> service.deactivateUser("viewer")).hasMessageContaining("Debe quedar");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void migrationClassifiesProvenHistoricalActionsWithoutInventingActors() throws Exception {
        jdbc.execute("create schema staff_audit_legacy");
        jdbc.execute("set local search_path to staff_audit_legacy");
        jdbc.execute("create table saas_admin_user (username text primary key)");
        jdbc.execute("insert into saas_admin_user values ('staff'),('system')");
        jdbc.execute("create table saas_admin_audit_log(id uuid primary key,username text,action text,created_at timestamptz)");
        jdbc.execute("""
                insert into saas_admin_audit_log values
                   (gen_random_uuid(),'staff','CREATE_ADMIN_USER',now()),
                   (gen_random_uuid(),'staff','CLAIM_STORE_REPAIR',now()),
                   (gen_random_uuid(),'system','CREATE_ADMIN_USER',now()),
                   (gen_random_uuid(),'unknown','CREATE_ADMIN_USER',now())
                """);
        try (var script = getClass().getResourceAsStream("/db/migration/V86__saas_staff_audit.sql")) {
            jdbc.execute(new String(script.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(jdbc.queryForList("select username from saas_admin_audit_log where origin='SAAS_ADMIN'", String.class))
                .containsExactly("staff");
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_audit_log where origin='OTHER'", Integer.class)).isEqualTo(3);
    }

    @Test
    void integrationIsNotQueuedUntilItsStaffAuditCommitsAndRetryDoesNotDuplicateTheAction() throws Exception {
        String token = "Bearer " + sessions.issue("admin","admin").token();
        var endpoint = service.createIntegration(new CreateIntegrationRequest(null, "Audit test", "WEBHOOK", "ACTIVA", null, null));
        UUID id = endpoint.id();
        jdbc.execute("create function fail_queue_evidence() returns trigger language plpgsql as $$ begin raise exception 'test rollback'; end; $$");
        jdbc.execute("create trigger fail_queue_evidence before insert on saas_admin_audit_log for each row when (new.action='EXECUTE_LOCAL_INTEGRATION') execute function fail_queue_evidence()");
        String path = "/api/v1/admin/integrations/" + id + "/sync";
        try {
            mvc.perform(post(path).header("Authorization",token).header("Idempotency-Key","private-retry-key"))
                    .andExpect(status().isConflict());
            assertThat(jdbc.queryForList("select status from saas_integration_run where integration_id=?",String.class,id)).containsExactly("FAILED");
        } finally {
            jdbc.execute("drop trigger fail_queue_evidence on saas_admin_audit_log");
            jdbc.execute("drop function fail_queue_evidence()");
        }
        for (int attempt=0; attempt<2; attempt++) {
            mvc.perform(post(path).header("Authorization",token).header("Idempotency-Key","private-retry-key")).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForList("select details from saas_admin_audit_log where action='EXECUTE_LOCAL_INTEGRATION' and username='admin' and target_id=?",String.class,id.toString()))
                .singleElement().asString().startsWith("run=").doesNotContain("private-retry-key");
    }

    @Test
    void appendOnlyDatabaseAndRollbackOfNewAccountOnAuditFailure() throws Exception {
        for (String sql : List.of("update saas_admin_audit_log set username='x' where false",
                "delete from saas_admin_audit_log where false", "truncate saas_admin_audit_log")) {
            assertThatThrownBy(() -> jdbc.execute(sql)).hasStackTraceContaining("Audit records are append-only");
        }
        String name = "rollback-staff";
        jdbc.execute("create function fail_staff_event() returns trigger language plpgsql as $$ begin raise exception 'test rollback'; end; $$");
        jdbc.execute("create trigger fail_staff_event before insert on saas_admin_audit_log for each row when (new.target_id='rollback-staff') execute function fail_staff_event()");
        try {
            assertThatThrownBy(() -> mvc.perform(post(USERS).header("Authorization","Bearer " + sessions.issue("admin","admin").token())
                    .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("username",name,"password","test-password","roleName","VIEWER")))))
                    .hasStackTraceContaining("test rollback");
            assertThat(jdbc.queryForObject("select count(*) from saas_admin_user where username=?",Integer.class,name)).isZero();
        } finally {
            jdbc.execute("drop trigger fail_staff_event on saas_admin_audit_log");
            jdbc.execute("drop function fail_staff_event()");
        }
    }
}

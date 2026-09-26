package com.tpverp.saas.supervision;

import static com.tpverp.saas.SaasTestData.validCif;
import static com.tpverp.saas.supervision.StoreRepairModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StoreRepairPostgreSqlTest {
    private static final String SCHEMA = "store_repair_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicInteger COMPANY = new AtomicInteger(9460000);
    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired StoreRepairService repairs;
    @Autowired com.tpverp.saas.admin.AdminService support;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void isolatedDatabase() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
    }

    @Test
    void adminPermissionsCreationIdempotencyAndInstallationClaimContract() throws Exception {
        Site site = site(); String failure = failure(site, "LOCAL_SYNC", "OPEN", "DANGER");
        mvc.perform(get(path(failure))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(failure)).header("Authorization", basic("viewer"))).andExpect(status().isOk());
        var request = new CreateRepairRequest(UUID.randomUUID(), "Reintentar entrega atascada");
        mvc.perform(post(path(failure)).header("Authorization", basic("viewer")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isForbidden());
        mvc.perform(post(manualPath(failure)).header("Authorization", basic("viewer")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new ManualRequest("Revisar manualmente")))).andExpect(status().isForbidden());
        var created = create(failure, request);
        assertThat(created.requestId()).isEqualTo(request.requestId());
        assertThat(created.expectedVersion()).isEqualTo(7);
        assertThat(created.companyId()).isEqualTo(site.company().getId());
        assertThat(created.storeId()).isEqualTo(site.store().getId());
        assertThat(created.requestedBy()).isEqualTo("admin");
        assertThat(created.status()).isEqualTo("QUEUED");
        assertThat(create(failure, request)).isEqualTo(created);
        assertThat(state(failure).remoteEligible()).isFalse();
        assertThat(state(failure).commands()).singleElement().isEqualTo(created);
        admin(post(path(failure)), new CreateRepairRequest(UUID.randomUUID(), "Otro intento duplicado"), 409);
        admin(post(path(failure)), new CreateRepairRequest(request.requestId(), "Motivo distinto"), 409);
        mvc.perform(post("/api/v1/sync/repairs/claim").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new ClaimRequest(site.installation().getInstallationId()))))
                .andExpect(status().isUnauthorized());
        String claim = installation(post("/api/v1/sync/repairs/claim"), site,
                new ClaimRequest(site.installation().getInstallationId()), 200);
        var claimed = mapper.readValue(claim, ClaimedCommand[].class);
        assertThat(claimed).hasSize(1);
        assertThat(claimed[0].companyId()).isEqualTo(site.company().getId());
        assertThat(claimed[0].storeId()).isEqualTo(site.store().getId());
        assertThat(claimed[0].commandId()).isEqualTo(created.commandId());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/phase2-central-repair-claim.json"), claim);
        assertThat(installation(post("/api/v1/sync/repairs/claim"), site,
                new ClaimRequest(site.installation().getInstallationId()), 200)).isEqualTo(claim);
        assertThat(countAudit(created.commandId(), "CREATE_STORE_REPAIR")).isEqualTo(1);
        assertThat(countAudit(created.commandId(), "CLAIM_STORE_REPAIR")).isEqualTo(1);
    }

    @Test
    void resultLifecycleIsIdempotentAndCannotReopenTerminalCommandOrResolveFailure() throws Exception {
        Site site = site(); String failure = failure(site, "LOCAL_SYNC", "OPEN", "DANGER");
        var command = create(failure);
        result(site, command.commandId(), "SUCCEEDED", "SYNC_DELIVERED", 409);
        claim(site);
        var running = result(site, command.commandId(), "RUNNING", "RETRY_QUEUED", 200);
        assertThat(result(site, command.commandId(), "RUNNING", "RETRY_QUEUED", 200)).isEqualTo(running);
        result(site, command.commandId(), "SUCCEEDED", "password=secret", 400);
        result(site, command.commandId(), "FAILED", null, 400);
        var succeeded = result(site, command.commandId(), "SUCCEEDED", "SYNC_DELIVERED", 200);
        assertThat(result(site, command.commandId(), "SUCCEEDED", "SYNC_DELIVERED", 200)).isEqualTo(succeeded);
        result(site, command.commandId(), "RUNNING", "RETRY_QUEUED", 409);
        result(site, command.commandId(), "FAILED", "RETRY_FAILED", 409);
        assertThat(countAudit(command.commandId(), "RESULT_STORE_REPAIR")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from saas_store_failure where id = ?", String.class, failureId(failure))).isEqualTo("OPEN");
        assertThat(state(failure).commands().getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(state(failure).remoteEligible()).isFalse();
        admin(post(path(failure)), new CreateRepairRequest(UUID.randomUUID(), "No repetir una version ya reparada"), 409);
        jdbc.update("update saas_store_failure set source_revision=8 where id=?", failureId(failure));
        assertThat(state(failure).remoteEligible()).isTrue();
        assertThat(create(failure).expectedVersion()).isEqualTo(8);
    }

    @Test
    void foreignTokensCommandsAndRequestIdsNeverModifyOwnedCommands() throws Exception {
        Site owner = site(); Site foreign = site();
        String ownFailure = failure(owner, "LOCAL_SYNC", "OPEN", "DANGER");
        String otherFailure = failure(foreign, "LOCAL_SYNC", "OPEN", "DANGER");
        var request = new CreateRepairRequest(UUID.randomUUID(), "Reintentar entrega fallida");
        var owned = create(ownFailure, request);
        var before = jdbc.queryForMap("select * from saas_store_repair_command where command_id = ?", owned.commandId());
        installation(post("/api/v1/sync/repairs/claim"), foreign,
                new ClaimRequest(owner.installation().getInstallationId()), 401);
        assertThat(claim(foreign)).isEmpty();
        result(foreign, owned.commandId(), "FAILED", "RETRY_FAILED", 404);
        admin(post(path(otherFailure)), request, 409);
        assertThat(jdbc.queryForMap("select * from saas_store_repair_command where command_id = ?", owned.commandId())).isEqualTo(before);
        assertThat(state(otherFailure).commands()).isEmpty();
        jdbc.update("update saas_installation set active=false,revoked_at=current_timestamp,revoked_by='test',revocation_reason='Revocacion de prueba' where id=?", owner.installation().getId());
        installation(post("/api/v1/sync/repairs/claim"), owner,
                new ClaimRequest(owner.installation().getInstallationId()), 401);
        result(owner, owned.commandId(), "FAILED", "RETRY_FAILED", 401);
    }

    @Test
    void expiredCommandsStopBeingClaimedAndLateSuccessRemainsExpired() throws Exception {
        Site site = site(); String failure = failure(site, "LOCAL_SYNC", "OPEN", "DANGER");
        var request = new CreateRepairRequest(UUID.randomUUID(), "Reintentar evento remoto");
        var queued = create(failure, request);
        forceExpiry(queued.commandId());
        assertThat(claim(site)).isEmpty();
        assertThat(state(failure).commands().getFirst().status()).isEqualTo("EXPIRED");
        assertThat(create(failure, request).commandId()).isEqualTo(queued.commandId());
        assertThat(result(site, queued.commandId(), "SUCCEEDED", "SYNC_DELIVERED", 200).status()).isEqualTo("EXPIRED");
        assertThat(countAudit(queued.commandId(), "EXPIRE_STORE_REPAIR")).isEqualTo(1);
        var next = create(failure);
        claim(site);
        forceExpiry(next.commandId());
        assertThat(result(site, next.commandId(), "SUCCEEDED", "SYNC_DELIVERED", 200).status()).isEqualTo("EXPIRED");
        assertThat(state(failure).commands()).hasSize(2);
        assertThat(jdbc.queryForObject("select status from saas_store_failure where id=?", String.class, failureId(failure))).isEqualTo("OPEN");
    }

    @Test
    void unsupportedAutomaticRetryAndReboundInstallationsRequireManualHandling() throws Exception {
        Site site = site();
        for (String failure : List.of(failure(site, "LOCAL_APPLICATION", "OPEN", "DANGER"),
                failure(site, "LOCAL_SYNC", "OPEN", "WARNING"), failure(site, "LOCAL_SYNC", "RESOLVED", "DANGER"))) {
            assertThat(state(failure).remoteEligible()).isFalse();
            admin(post(path(failure)), new CreateRepairRequest(UUID.randomUUID(), "Reintento no permitido"), 409);
        }
        String failure = failure(site, "LOCAL_SYNC", "OPEN", "DANGER");
        SaasStore otherStore = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), site.company(), "002", "Rebound store", "Atlantic/Canary", Instant.now()));
        jdbc.update("update saas_installation set store_id=? where id=?", otherStore.getId(), site.installation().getId());
        assertThat(state(failure).remoteEligible()).isFalse();
        admin(post(path(failure)), new CreateRepairRequest(UUID.randomUUID(), "No cambiar de tienda"), 409);
    }

    @Test
    void manualEscalationReusesOneCompanyTicketAndIncludesRepairOutcome() throws Exception {
        Site site = site(); String failure = failure(site, "LOCAL_SYNC", "OPEN", "DANGER");
        var command = create(failure);
        claim(site);
        result(site, command.commandId(), "FAILED", "STALE_EVENT", 200);
        var request = new ManualRequest("La version cambio y requiere revision");
        var first = mapper.readValue(admin(post(manualPath(failure)), request, 200), ManualResponse.class);
        assertThat(mapper.readValue(admin(post(manualPath(failure)), request, 200), ManualResponse.class)).isEqualTo(first);
        assertThat(state(failure).manualTicketId()).isEqualTo(first.ticketId());
        var ticket = jdbc.queryForMap("select company_id,description,status from saas_support_ticket where id=?", first.ticketId());
        assertThat(ticket.get("company_id")).isEqualTo(site.company().getId());
        assertThat(ticket.get("description").toString()).contains(failure, command.commandId().toString(), "STALE_EVENT", "FAILED");
        assertThat(ticket.get("status")).isEqualTo("ABIERTO");
        assertThat(jdbc.queryForObject("select count(*) from saas_store_failure_manual where failure_key=?", Long.class, failure)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='ESCALATE_STORE_FAILURE' and target_id=?",
                Long.class, first.ticketId().toString())).isEqualTo(1);
        String application = failure(site, "LOCAL_APPLICATION", "OPEN", "DANGER");
        assertThat(mapper.readValue(admin(post(manualPath(application)), new ManualRequest("Revisar fallo de aplicacion"), 200),
                ManualResponse.class).ticketId()).isNotNull();
    }

    @Test
    void concurrentCreationKeepsOneCommandForAnIdempotencyKey() throws Exception {
        Site site = site(); String failure = failure(site, "LOCAL_SYNC", "OPEN", "DANGER");
        var request = new CreateRepairRequest(UUID.randomUUID(), "Reintentar una sola vez");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return repairs.create(failure, request); });
            var second = executor.submit(() -> { start.await(); return repairs.create(failure, request); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(repairs.state(failure).commands()).hasSize(1);
    }

    @Test
    void claimIsBoundedAndRepeatedClaimsDoNotChangeExecutionIdentity() {
        Site site = site();
        for (int i = 0; i < 11; i++) repairs.create(failure(site, "LOCAL_SYNC", "OPEN", "DANGER"),
                new CreateRepairRequest(UUID.randomUUID(), "Reintentar una entrega"));
        var first = repairs.claim(site.installation().getInstallationId(), site.token());
        assertThat(first).hasSize(10);
        var second = repairs.claim(site.installation().getInstallationId(), site.token());
        assertThat(second).hasSize(10);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).map(ClaimedCommand::commandId).distinct()).hasSize(11);
        assertThat(first).extracting(ClaimedCommand::commandId).doesNotContain(second.getFirst().commandId());
        repairs.result(first.getFirst().commandId(),
                new ResultRequest(site.installation().getInstallationId(), "SUCCEEDED", "SYNC_DELIVERED"), site.token());
        var next = repairs.claim(site.installation().getInstallationId(), site.token());
        assertThat(next).hasSize(10);
        assertThat(next).extracting(ClaimedCommand::commandId).doesNotContain(first.getFirst().commandId());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TPV_PHASE2_RESULT_FIXTURE", matches = ".+")
    void consumesActualLocalWorkerReceipt() throws Exception {
        Site site = site(); var command = create(failure(site, "LOCAL_SYNC", "OPEN", "DANGER"));
        claim(site);
        var captured = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                Files.readString(Path.of(System.getenv("TPV_PHASE2_RESULT_FIXTURE"))));
        java.util.Set<String> fields = new java.util.HashSet<>();
        captured.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("installationId", "status", "resultCode");
        captured.put("installationId", site.installation().getInstallationId().toString());
        String responseJson = mvc.perform(post("/api/v1/sync/repairs/" + command.commandId() + "/result")
                        .header("X-TPV-Installation-Token", site.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(captured)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var response = mapper.readValue(responseJson, RepairCommandView.class);
        assertThat(response.status()).isEqualTo(captured.path("status").asText());
        assertThat(response.resultCode()).isEqualTo(captured.path("resultCode").asText());
    }

    @Test
    void authorizationIsRecheckedAfterWaitingForInstallationLock() throws Exception {
        Site revoked = site(); var queued = create(failure(revoked, "LOCAL_SYNC", "OPEN", "DANGER"));
        assertThat(whileWaiting(revoked, () -> responseCode(() -> repairs.claim(revoked.installation().getInstallationId(), revoked.token())),
                () -> jdbc.update("update saas_installation set active=false,revoked_at=current_timestamp,revoked_by='test',revocation_reason='Revoked while waiting' where id=?", revoked.installation().getId())))
                .isEqualTo(401);
        assertThat(commandStatus(queued.commandId())).isEqualTo("QUEUED");
        assertThat(countAudit(queued.commandId(), "CLAIM_STORE_REPAIR")).isZero();

        Site rotated = site(); var running = create(failure(rotated, "LOCAL_SYNC", "OPEN", "DANGER")); claim(rotated);
        String replacement = tokens.newToken();
        var receipt = new ResultRequest(rotated.installation().getInstallationId(), "SUCCEEDED", "SYNC_DELIVERED");
        assertThat(whileWaiting(rotated, () -> responseCode(() -> repairs.result(running.commandId(), receipt, rotated.token())),
                () -> jdbc.update("update saas_installation set token_hash=? where id=?", tokens.hash(replacement), rotated.installation().getId())))
                .isEqualTo(401);
        assertThat(commandStatus(running.commandId())).isEqualTo("RUNNING");
        assertThat(repairs.result(running.commandId(), receipt, replacement).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void reboundAndDisabledStoresCannotReceiveQueuedCommandsAfterLockWait() throws Exception {
        Site rebound = site(); var queued = create(failure(rebound, "LOCAL_SYNC", "OPEN", "DANGER"));
        SaasStore replacement = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), rebound.company(), "002", "Replacement", "Atlantic/Canary", Instant.now()));
        assertThat(whileWaiting(rebound, () -> repairs.claim(rebound.installation().getInstallationId(), rebound.token()),
                () -> jdbc.update("update saas_installation set store_id=? where id=?", replacement.getId(), rebound.installation().getId())))
                .isEmpty();
        assertThat(commandStatus(queued.commandId())).isEqualTo("QUEUED");
        Site disabled = site(); var other = create(failure(disabled, "LOCAL_SYNC", "OPEN", "DANGER"));
        assertThat(whileWaiting(disabled, () -> responseCode(() -> repairs.claim(disabled.installation().getInstallationId(), disabled.token())),
                () -> jdbc.update("update saas_store set active=false where id=?", disabled.store().getId())))
                .isEqualTo(401);
        assertThat(commandStatus(other.commandId())).isEqualTo("QUEUED");
    }

    @Test
    void creationReadsFailureStatusAndRevisionAfterWaitingForInstallationLock() throws Exception {
        Site resolved = site(); String first = failure(resolved, "LOCAL_SYNC", "OPEN", "DANGER");
        assertThat(whileWaiting(resolved, () -> responseCode(() -> repairs.create(first, new CreateRepairRequest(UUID.randomUUID(), "Retry after diagnosis"))),
                () -> jdbc.update("update saas_store_failure set status='RESOLVED',source_revision=8 where id=?", failureId(first))))
                .isEqualTo(409);
        assertThat(repairs.state(first).commands()).isEmpty();
        Site changed = site(); String second = failure(changed, "LOCAL_SYNC", "OPEN", "DANGER");
        assertThat(whileWaiting(changed, () -> repairs.create(second, new CreateRepairRequest(UUID.randomUUID(), "Retry latest diagnosis")),
                () -> jdbc.update("update saas_store_failure set source_revision=8 where id=?", failureId(second))).expectedVersion())
                .isEqualTo(8);
    }

    @Test
    void differentConcurrentRequestsHaveOneWinnerAndManualEscalationDoesNotDeadlock() throws Exception {
        Site site = site(); String failure = failure(site, "LOCAL_SYNC", "OPEN", "DANGER");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> { start.await(); return responseCode(() -> repairs.create(failure, new CreateRepairRequest(UUID.randomUUID(), "First retry request"))); });
            var second = executor.submit(() -> { start.await(); return responseCode(() -> repairs.create(failure, new CreateRepairRequest(UUID.randomUUID(), "Second retry request"))); });
            var manual = executor.submit(() -> { start.await(); return repairs.manual(failure, new ManualRequest("Manual investigation")); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(repairs.manual(failure, new ManualRequest("Repeated escalation"))).isEqualTo(manual.get(15, TimeUnit.SECONDS));
        }
        assertThat(repairs.state(failure).commands()).hasSize(1);
    }

    @Test
    void commentLostResponseRetryPreservesIdentityAndRejectsChangedAuthorOrText() throws Exception {
        Site site = site(); UUID ticket = repairs.manual(failure(site, "LOCAL_APPLICATION", "OPEN", "DANGER"), new ManualRequest("Manual investigation")).ticketId();
        String url = "/api/v1/admin/tickets/" + ticket + "/comments";
        UUID key = UUID.randomUUID();
        var request = new com.tpverp.saas.admin.CreateSupportTicketCommentRequest("Investigacion iniciada", key);
        String first = admin(post(url), request, 200);
        assertThat(admin(post(url), request, 200)).isEqualTo(first);
        assertThat(mapper.readTree(first).path("requestId").asText()).isEqualTo(key.toString());
        assertThat(mapper.readTree(admin(get(url), null, 200))).hasSize(1);
        assertThat(mapper.readTree(admin(get(url), null, 200)).get(0).path("requestId").asText()).isEqualTo(key.toString());
        admin(post(url), new com.tpverp.saas.admin.CreateSupportTicketCommentRequest("Texto diferente", key), 409);
        // Direct service has actor system, so it cannot reuse an admin-authored request.
        assertThat(responseCode(() -> support.createSupportTicketComment(ticket, request))).isEqualTo(409);
        assertThat(countAudit(ticket, "ADD_SUPPORT_TICKET_COMMENT")).isEqualTo(1);
        var legacy = new com.tpverp.saas.admin.CreateSupportTicketCommentRequest("Legacy append");
        assertThat(mapper.readTree(admin(post(url), legacy, 200)).path("id").asText())
                .isNotEqualTo(mapper.readTree(admin(post(url), legacy, 200)).path("id").asText());
    }

    @Test
    void concurrentCommentRetriesInsertAndAuditOnlyOnce() throws Exception {
        Site site = site(); UUID ticket = repairs.manual(failure(site, "LOCAL_APPLICATION", "OPEN", "DANGER"), new ManualRequest("Manual investigation")).ticketId();
        var request = new com.tpverp.saas.admin.CreateSupportTicketCommentRequest("Concurrent comment", UUID.randomUUID());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return support.createSupportTicketComment(ticket, request); });
            var second = executor.submit(() -> { start.await(); return support.createSupportTicketComment(ticket, request); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_support_ticket_comment where ticket_id=?", Long.class, ticket)).isEqualTo(1);
        assertThat(countAudit(ticket, "ADD_SUPPORT_TICKET_COMMENT")).isEqualTo(1);
    }

    @Test
    void invalidCommentUnicodeIsRejectedWithoutPoisoningIdempotencyKey() throws Exception {
        Site site = site(); UUID ticket = repairs.manual(failure(site, "LOCAL_APPLICATION", "OPEN", "DANGER"), new ManualRequest("Manual investigation")).ticketId();
        UUID key=UUID.randomUUID();
        String url="/api/v1/admin/tickets/"+ticket+"/comments";
        for (String sequence : List.of("u0000", "uD800", "uDC00")) {
            String escaped = "valid" + (char) 92 + sequence + "comment";
            String raw=mapper.writeValueAsString(new com.tpverp.saas.admin.CreateSupportTicketCommentRequest("PLACEHOLDER",key)).replace("PLACEHOLDER",escaped);
            assertThat(raw).contains(escaped).doesNotContain("?");
            mvc.perform(post(url).header("Authorization",basic("admin")).contentType(MediaType.APPLICATION_JSON).content(raw))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select count(*) from saas_support_ticket_comment where ticket_id=?",Long.class,ticket)).isZero();
        assertThat(countAudit(ticket,"ADD_SUPPORT_TICKET_COMMENT")).isZero();
        var request=new com.tpverp.saas.admin.CreateSupportTicketCommentRequest("Valid comment after correction",key);
        assertThat(admin(post(url),request,200)).isEqualTo(admin(post(url),request,200));
        assertThat(countAudit(ticket,"ADD_SUPPORT_TICKET_COMMENT")).isEqualTo(1);
    }
    private String commandStatus(UUID command) {
        return jdbc.queryForObject("select status from saas_store_repair_command where command_id=?", String.class, command);
    }
    private int responseCode(Runnable action) {
        try { action.run(); return 200; }
        catch (org.springframework.web.server.ResponseStatusException ex) { return ex.getStatusCode().value(); }
    }
    private <T> T whileWaiting(Site site, Callable<T> action, Runnable mutation) throws Exception {
        String key = "store-repair-installation:" + site.installation().getId();
        var executor = Executors.newSingleThreadExecutor();
        try (var blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, key); statement.execute();
            }
            try {
                var waiting = executor.submit(action);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < deadline && !waiting.isDone()) {
                    blocked = Boolean.TRUE.equals(jdbc.queryForObject("""
                            select exists(select 1 from pg_locks where locktype='advisory' and not granted
                            and database=(select oid from pg_database where datname=current_database())
                            and classid=((hashtextextended(?,0)>>32)&4294967295)::oid
                            and objid=(hashtextextended(?,0)&4294967295)::oid)
                            """, Boolean.class, key, key));
                    if (blocked) break;
                    Thread.sleep(20);
                }
                assertThat(blocked).as("operation is waiting after its initial read").isTrue();
                mutation.run();
                blocker.commit();
                return waiting.get(15, TimeUnit.SECONDS);
            } finally { blocker.rollback(); }
        } finally { executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS); }
    }
    private RepairCommandView create(String failure) throws Exception {
        return create(failure, new CreateRepairRequest(UUID.randomUUID(), "Reintentar entrega fallida"));
    }
    private RepairCommandView create(String failure, CreateRepairRequest request) throws Exception {
        return mapper.readValue(admin(post(path(failure)), request, 200), RepairCommandView.class);
    }
    private RepairState state(String failure) throws Exception {
        return mapper.readValue(admin(get(path(failure)), null, 200), RepairState.class);
    }
    private List<ClaimedCommand> claim(Site site) throws Exception {
        return List.of(mapper.readValue(installation(post("/api/v1/sync/repairs/claim"), site,
                new ClaimRequest(site.installation().getInstallationId()), 200), ClaimedCommand[].class));
    }
    private RepairCommandView result(Site site, UUID id, String status, String code, int expected) throws Exception {
        String response = installation(post("/api/v1/sync/repairs/" + id + "/result"), site,
                new ResultRequest(site.installation().getInstallationId(), status, code), expected);
        return expected == 200 ? mapper.readValue(response, RepairCommandView.class) : null;
    }
    private String admin(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", basic("admin"));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private String installation(MockHttpServletRequestBuilder request, Site site, Object body, int expected) throws Exception {
        return mvc.perform(request.header("X-TPV-Installation-Token", site.token()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private static String path(String failure) { return "/api/v1/admin/supervision/failures/" + failure + "/repairs"; }
    private static String manualPath(String failure) { return "/api/v1/admin/supervision/failures/" + failure + "/manual"; }
    private static UUID failureId(String failure) { return UUID.fromString(failure.substring(failure.indexOf(':') + 1)); }
    private long countAudit(UUID command, String action) {
        return jdbc.queryForObject("select count(*) from saas_admin_audit_log where target_id=? and action=?", Long.class, command.toString(), action);
    }
    private void forceExpiry(UUID command) {
        jdbc.update("update saas_store_repair_command set created_at=?,expires_at=? where command_id=?",
                Timestamp.from(Instant.now().minusSeconds(1800)), Timestamp.from(Instant.now().minusSeconds(1)), command);
    }
    private String failure(Site site, String source, String status, String severity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_store_failure(id,company_id,store_id,installation_id,source,source_id,source_revision,status,severity,
                    code,first_seen_at,last_seen_at,received_at,occurrences)
                values (?,?,?,?,?,?,7,?,?,?,?,?,?,1)
                """, id, site.company().getId(), site.store().getId(), site.installation().getId(), source, UUID.randomUUID(), status, severity,
                source.equals("LOCAL_SYNC") ? "SYNC_DELIVERY_FAILED" : "APPLICATION_ERROR",
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(30)), Timestamp.from(Instant.now()));
        return source + ":" + id;
    }
    private Site site() {
        SaasCompany company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Repair test",
                validCif("B" + COMPANY.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD, TaxRegime.IVA, Instant.now()));
        SaasStore store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, "001", "Repair store", "Atlantic/Canary", Instant.now()));
        SaasLicense license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company, "REPAIR-" + UUID.randomUUID(),
                Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        SaasInstallation installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company, store, license,
                UUID.randomUUID(), "REPAIR-INSTALLATION", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }
    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":admin").getBytes(StandardCharsets.UTF_8));
    }
    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) { }
}

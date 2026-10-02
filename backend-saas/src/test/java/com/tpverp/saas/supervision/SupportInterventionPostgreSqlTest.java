package com.tpverp.saas.supervision;

import static com.tpverp.saas.SaasTestData.validCif;
import static com.tpverp.saas.supervision.SupportInterventionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.*;
import com.tpverp.saas.license.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SupportInterventionPostgreSqlTest {
    private static final String SCHEMA = "support_intervention_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicInteger COMPANY = new AtomicInteger(9480000);
    @DynamicPropertySource
    static void schema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Autowired SupportInterventionService interventions;
    @Autowired StoreRepairService repairs;
    @Autowired AdminService support;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void httpPermissionsLinkedCompanyAndInputValidation() throws Exception {
        Linked linked = linked(); UUID ticket = linked.ticket();
        mvc.perform(get(path(ticket))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(ticket)).header("Authorization", basic("viewer"))).andExpect(status().isOk());
        Request request = request(interventions.state(ticket), "START_REMOTE", "123456789");
        mvc.perform(post(path(ticket)).header("Authorization", basic("viewer")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isForbidden());
        for (String invalid : List.of("12345", "1234567890123456", "123 456", "password=secret")) {
            submit(ticket, new Request(UUID.randomUUID(), 0L, "ABIERTO", "START_REMOTE", "Valid investigation", invalid), 400);
        }
        submit(ticket, new Request(UUID.randomUUID(), -1L, "ABIERTO", "START_REMOTE", "Valid investigation", null), 400);
        submit(ticket, new Request(UUID.randomUUID(), 0L, "ABIERTO", "START_REMOTE", "   a   ", null), 400);
        submit(ticket, new Request(UUID.randomUUID(), 0L, "ABIERTO", "START_REMOTE", "x".repeat(2001), null), 400);
        UUID ordinary = support.createSupportTicket(linked.site().company().getId(), new CreateSupportTicketRequest("Ordinary ticket", "Normal support", "NORMAL")).id();
        http(get(path(ordinary)), null, 200);
        submit(ordinary, request, 200);
        submit(ticket, new Request(UUID.randomUUID(), 0L, "ABIERTO", "START_REMOTE", "😀😀😀", null), 400);
        Site foreign = site();
        jdbc.update("update saas_store_failure_manual set company_id=? where ticket_id=?", foreign.company().getId(), ticket);
        http(get(path(ticket)), null, 404);
        submit(ticket, request, 404);
        assertThat(jdbc.queryForObject("select count(*) from saas_support_intervention_event where ticket_id=?", Long.class, ticket)).isZero();
    }

    @Test
    void remoteOnsiteResolutionAndReopeningPreserveDiagnosticsAndPrivateNotes() throws Exception {
        Linked linked = linked(); UUID ticket = linked.ticket();
        Map<String,Object> original = jdbc.queryForMap("select * from saas_store_failure where id=?", failureId(linked.failure()));
        var originalTicket = jdbc.queryForMap("select description from saas_support_ticket where id=?", ticket);
        State state = read(ticket);
        assertThat(state.status()).isEqualTo("REMOTE_PENDING"); assertThat(state.version()).isZero();
        state = apply(ticket, state, "START_REMOTE", "123456789");
        assertThat(state.status()).isEqualTo("REMOTE_IN_PROGRESS"); assertThat(state.ticketStatus()).isEqualTo("EN_CURSO");
        submit(ticket, request(state, "REQUIRE_ONSITE", "987654321"), 400);
        assertThat(read(ticket)).isEqualTo(state);
        state = apply(ticket, state, "REQUIRE_ONSITE", null);
        assertThat(state.status()).isEqualTo("ONSITE_REQUIRED"); assertThat(state.teamViewerId()).isEqualTo("123456789");
        state = submit(ticket, planning(state, "Technician Ana"), 200);
        state = apply(ticket, state, "START_ONSITE", null);
        state = close(ticket, state);
        assertThat(state.status()).isEqualTo("RESOLVED"); assertThat(state.ticketStatus()).isEqualTo("RESUELTO");
        state = apply(ticket, state, "REOPEN", null);
        assertThat(state.status()).isEqualTo("REMOTE_PENDING"); assertThat(state.ticketStatus()).isEqualTo("ABIERTO");
        state = apply(ticket, state, "START_REMOTE", "");
        assertThat(state.teamViewerId()).isNull();
        assertThat(state.events()).hasSize(8);
        assertThat(state.events()).extracting(Event::actor).containsOnly("admin");
        assertThat(state.events()).extracting(Event::version).containsExactly(1L,2L,3L,4L,5L,6L,7L,8L);
        assertThat(audits(ticket)).isEqualTo(8);
        assertThat(jdbc.queryForMap("select * from saas_store_failure where id=?", failureId(linked.failure()))).isEqualTo(original);
        assertThat(jdbc.queryForMap("select description from saas_support_ticket where id=?", ticket)).isEqualTo(originalTicket);
        assertThat(jdbc.queryForObject("select count(*) from saas_support_ticket_comment where ticket_id=?", Long.class, ticket)).isZero();
    }

    @Test
    void illegalTransitionsAndStaleVersionOrTicketStatusDoNotMutateState() throws Exception {
        UUID ticket = linked().ticket(); State initial = read(ticket);
        for (String action : List.of("START_ONSITE", "RESOLVE", "REOPEN")) submit(ticket, request(initial, action, null), 409);
        submit(ticket, new Request(UUID.randomUUID(), 0L, "EN_CURSO", "START_REMOTE", "Valid investigation", null), 409);
        State onsite = apply(ticket, initial, "REQUIRE_ONSITE", null);
        submit(ticket, request(initial, "START_REMOTE", null), 409);
        submit(ticket, request(onsite, "RESOLVE", null), 409);
        assertThat(read(ticket)).isEqualTo(onsite);
        assertThat(audits(ticket)).isEqualTo(1);
    }

    @Test
    void retryReturnsCurrentStateAndRejectsReusedKeyAcrossPayloadActorAndTicket() throws Exception {
        UUID ticket = linked().ticket(); Request start = request(read(ticket), "START_REMOTE", "123456789");
        State first = submit(ticket, start, 200);
        State resolved = close(ticket, first);
        assertThat(submit(ticket, start, 200)).isEqualTo(resolved);
        Request whitespace = new Request(start.requestId(), start.expectedVersion(), start.expectedTicketStatus(), start.action(), "  " + start.note() + "  ", start.teamViewerId());
        assertThat(submit(ticket, whitespace, 200)).isEqualTo(resolved);
        submit(ticket, new Request(start.requestId(), 0L, "ABIERTO", "START_REMOTE", "Changed note", start.teamViewerId()), 409);
        submit(ticket, new Request(start.requestId(), 0L, "ABIERTO", "START_REMOTE", start.note(), "987654321"), 409);
        assertThat(responseCode(() -> interventions.apply(ticket, start))).isEqualTo(409); // system is not original admin
        submit(linked().ticket(), start, 409);
        assertThat(read(ticket)).isEqualTo(resolved);
        assertThat(audits(ticket)).isEqualTo(3);
    }

    @Test
    void sameKeyConcurrencyIsIdempotentAndDifferentKeysUseCompareAndSet() throws Exception {
        UUID ticket = linked().ticket(); Request request = request(interventions.state(ticket), "START_REMOTE", null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return interventions.apply(ticket, request); });
            var b = executor.submit(() -> { start.await(); return interventions.apply(ticket, request); });
            start.countDown(); assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        State current = interventions.state(ticket);
        CountDownLatch second = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { second.await(); return responseCode(() -> interventions.apply(ticket, request(current, "REQUEST_VERIFICATION", null))); });
            var b = executor.submit(() -> { second.await(); return responseCode(() -> interventions.apply(ticket, request(current, "REQUIRE_ONSITE", null))); });
            second.countDown(); assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(interventions.state(ticket).events()).hasSize(2);
        assertThat(audits(ticket)).isEqualTo(2);
    }

    @Test
    void genericStatusChangesCannotBypassVerificationOrReopening() throws Exception {
        UUID ticket = linked().ticket(); State initial = read(ticket);
        assertThat(responseCode(() -> changeTicketStatus(ticket, "RESUELTO"))).isEqualTo(409);
        assertThat(responseCode(() -> changeTicketStatus(ticket, "EN_CURSO"))).isEqualTo(409);
        assertThat(read(ticket)).isEqualTo(initial);
        State started = apply(ticket, initial, "START_REMOTE", "123456789");
        submit(ticket, request(started, "RESOLVE", null), 409);
        State closed = close(ticket, started);
        assertThat(responseCode(() -> changeTicketStatus(ticket, "ABIERTO"))).isEqualTo(409);
        State reopened = apply(ticket, closed, "REOPEN", null);
        submit(ticket, request(initial, "START_REMOTE", null), 409);
        support.updateSupportTicket(ticket, new UpdateSupportTicketRequest(null, "URGENTE"));
        assertThat(read(ticket)).isEqualTo(reopened);
        assertThat(reopened.resolutionSummary()).isNull(); assertThat(reopened.verificationNotes()).isNull();
        assertThat(reopened.events()).hasSize(4);
        assertThat(reopened.events().get(2).confirmedBy()).isEqualTo("Store manager");
        assertThat(reopened.failure().status()).isEqualTo("OPEN");
    }

    @Test
    void generalTicketsUseSameWorkflowAndFailedVerificationReturnsToDiagnosis() throws Exception {
        Site site = site(); UUID ticket = support.createSupportTicket(site.company().getId(), new CreateSupportTicketRequest("Ordinary", "Support", "NORMAL")).id();
        assertThat(responseCode(() -> changeTicketStatus(ticket, "RESUELTO"))).isEqualTo(409);
        State initial = read(ticket); assertThat(initial.failure()).isNull();
        State remote = apply(ticket, initial, "START_REMOTE", null);
        submit(ticket, new Request(UUID.randomUUID(), remote.version(), remote.ticketStatus(), "REQUEST_VERIFICATION", "Check repair", null), 400);
        State checking = apply(ticket, remote, "REQUEST_VERIFICATION", null);
        submit(ticket, new Request(UUID.randomUUID(), checking.version(), checking.ticketStatus(), "RESOLVE", "Check repair", null), 400);
        State failed = apply(ticket, checking, "VERIFICATION_FAILED", null);
        assertThat(failed.status()).isEqualTo("REMOTE_PENDING"); assertThat(failed.ticketStatus()).isEqualTo("ABIERTO");
        assertThat(failed.resolutionSummary()).isNull();
        State closed = close(ticket, apply(ticket, failed, "START_REMOTE", null));
        assertThat(closed.verificationNotes()).isEqualTo("Test sale and printing succeeded");
        assertThat(closed.confirmedBy()).isEqualTo("Store manager");
        assertThat(support.updateSupportTicket(ticket, new UpdateSupportTicketRequest(null, "ALTA")).status()).isEqualTo("RESUELTO");
    }

    @Test
    void planningRequiresAssigneeAndVisitAndHasIdempotentSnapshot() throws Exception {
        UUID ticket = linked().ticket(); State initial = read(ticket);
        State onsite = apply(ticket, initial, "REQUIRE_ONSITE", null);
        submit(ticket, request(onsite, "START_ONSITE", null), 409);
        Request plan = planning(onsite, "Technician Ana");
        State planned = submit(ticket, plan, 200);
        assertThat(submit(ticket, plan, 200)).isEqualTo(planned);
        submit(ticket, new Request(plan.requestId(), plan.expectedVersion(), plan.expectedTicketStatus(), plan.action(), plan.note(), null, null, plan.visitAt(), null, null, null, technicianId("Technician Bea"), null), 409);
        assertThat(planned.assignee()).isEqualTo("Technician Ana"); assertThat(planned.visitAt()).isEqualTo(plan.visitAt());
        State working = apply(ticket, planned, "START_ONSITE", null);
        submit(ticket, new Request(UUID.randomUUID(), working.version(), working.ticketStatus(), "SAVE_DETAILS", "Clear visit date", null, null, null, null, null, null, technicianId("Technician Ana"), null), 400);
        assertThat(close(ticket, working).events()).hasSize(5);
    }

    @Test
    void historicalClosedTicketsStayClosedWithoutInventingEvidence() throws Exception {
        UUID ticket = linked().ticket(); jdbc.update("update saas_support_ticket set status='RESUELTO' where id=?", ticket);
        State old = read(ticket); assertThat(old.status()).isEqualTo("RESOLVED"); assertThat(old.events()).isEmpty();
        assertThat(old.verificationNotes()).isNull(); assertThat(old.confirmedBy()).isNull();
        assertThat(apply(ticket, old, "REOPEN", null).status()).isEqualTo("REMOTE_PENDING");
    }

    @Test
    void ordinaryPartialUpdateWaitingForWorkflowCannotRestoreOldTicketStatus() throws Exception {
        UUID ticket = linked().ticket(); State initial = interventions.state(ticket);
        var executor = Executors.newSingleThreadExecutor();
        try (var blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var st = blocker.createStatement(); var rs = st.executeQuery("select pg_backend_pid()")) { rs.next(); blockerPid = rs.getInt(1); }
            try (var st = blocker.prepareStatement("select id from saas_support_ticket where id=? for update")) { st.setObject(1,ticket); st.execute(); }
            try {
                var waiting = executor.submit(() -> support.updateSupportTicket(ticket, new UpdateSupportTicketRequest(null, "URGENTE")));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); boolean blocked = false;
                while (!waiting.isDone() && System.nanoTime() < deadline) {
                    blocked = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))", Boolean.class, blockerPid));
                    if (blocked) break; Thread.sleep(20);
                }
                assertThat(blocked).isTrue();
                try (var st = blocker.prepareStatement("update saas_support_ticket set status='RESUELTO' where id=?")) { st.setObject(1,ticket); st.executeUpdate(); }
                blocker.commit();
                assertThat(waiting.get(15, TimeUnit.SECONDS).status()).isEqualTo("RESUELTO");
                assertThat(interventions.state(ticket).status()).isEqualTo("RESOLVED");
            } finally { blocker.rollback(); }
        } finally { executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS); }
    }

    @Test
    void unicodeInputRejectsUnpersistableTextAndPreservesTwoThousandCodePoints() throws Exception {
        UUID ticket = linked().ticket(); State initial = read(ticket);
        Request template = request(initial, "START_REMOTE", null);
        for (String sequence : List.of("u0000", "uD800", "uDC00")) {
            String escaped = "valid" + (char) 92 + sequence + "note";
            String raw = mapper.writeValueAsString(template).replace(template.note(), escaped);
            assertThat(raw).contains(escaped).doesNotContain("?");
            mvc.perform(post(path(ticket)).header("Authorization", basic("admin")).contentType(MediaType.APPLICATION_JSON)
                    .content(raw)).andExpect(status().isBadRequest());
        }
        assertThat(read(ticket)).isEqualTo(initial);
        String note = "😀".repeat(2000);
        Request maximum = new Request(UUID.randomUUID(), 0L, "ABIERTO", "START_REMOTE", note, null);
        State accepted = submit(ticket, maximum, 200);
        assertThat(accepted.events()).singleElement().extracting(Event::note).isEqualTo(note);
        assertThat(submit(ticket, maximum, 200)).isEqualTo(accepted);
        assertThat(jdbc.queryForObject("select length(note) from saas_support_intervention_event where request_id=?", Integer.class, maximum.requestId())).isEqualTo(2000);
        submit(ticket, new Request(UUID.randomUUID(), 1L, "EN_CURSO", "RESOLVE", note + "😀", null), 400);
        assertThat(audits(ticket)).isEqualTo(1);
    }

    @Test
    void newRemoteSessionCanReplaceOrClearIdWithoutChangingHistoricalEvents() throws Exception {
        UUID ticket = linked().ticket(); State first = apply(ticket, read(ticket), "START_REMOTE", "111111111");
        State closed = close(ticket, first);
        State pending = apply(ticket, closed, "REOPEN", null);
        assertThat(pending.teamViewerId()).isEqualTo("111111111");
        State second = apply(ticket, pending, "START_REMOTE", "222222222");
        assertThat(second.teamViewerId()).isEqualTo("222222222");
        closed = close(ticket, second);
        pending = apply(ticket, closed, "REOPEN", null);
        State cleared = apply(ticket, pending, "START_REMOTE", null);
        assertThat(cleared.teamViewerId()).isNull();
        assertThat(cleared.events().subList(0, first.events().size())).isEqualTo(first.events());
        assertThat(cleared.events().get(4).teamViewerId()).isEqualTo("222222222");
        assertThat(cleared.events().getLast().teamViewerId()).isNull();
    }

    @Test
    void genericTicketHttpStatusWritesRequireFreshLinkedSnapshotAndExplainVersionGaps() throws Exception {
        Linked linked = linked(); UUID ticket = linked.ticket();
        String listPath = "/api/v1/admin/companies/" + linked.site().company().getId() + "/tickets";
        var listed = mapper.readValue(http(get(listPath),null,200), SupportTicketResponse[].class);
        assertThat(listed).singleElement().extracting(SupportTicketResponse::interventionVersion).isEqualTo(0L);
        State started = apply(ticket, read(ticket), "START_REMOTE", "123456789");
        State onsite = apply(ticket, started, "REQUIRE_ONSITE", null);
        String updatePath = "/api/v1/admin/tickets/" + ticket;
        long before = updateAudits(ticket);
        http(put(updatePath), new UpdateSupportTicketRequest("RESUELTO", null), 409);
        http(put(updatePath), new UpdateSupportTicketRequest("RESUELTO", null, 1L, "EN_CURSO"), 409);
        http(put(updatePath), new UpdateSupportTicketRequest("EN_CURSO", null, 1L, "EN_CURSO"), 409);
        assertThat(read(ticket)).isEqualTo(onsite); assertThat(updateAudits(ticket)).isEqualTo(before);
        var priority = mapper.readValue(http(put(updatePath),new UpdateSupportTicketRequest(null,"URGENTE"),200),SupportTicketResponse.class);
        assertThat(priority.status()).isEqualTo("EN_CURSO"); assertThat(priority.interventionVersion()).isEqualTo(2);
        http(put(updatePath),new UpdateSupportTicketRequest("RESUELTO",null,2L,"EN_CURSO"),409);
        assertThat(read(ticket)).isEqualTo(onsite);
        assertThat(updateAudits(ticket)).isEqualTo(before + 1);
        assertThat(read(ticket).events()).isEqualTo(onsite.events());
    }

    @Test
    void auditCommitFailureRollsBackTicketWorkflowReceiptAndAllowsSameKeyRetry() throws Exception {
        UUID ticket = linked().ticket(); State initial = read(ticket); Request request = request(initial,"START_REMOTE","123456789");
        var ticketBefore = jdbc.queryForMap("select * from saas_support_ticket where id=?",ticket);
        jdbc.execute("create function fail_intervention_audit() returns trigger language plpgsql as $$ begin raise exception 'simulated audit commit failure' using errcode='23514'; end $$");
        jdbc.execute("create constraint trigger fail_intervention_audit after insert on saas_admin_audit_log deferrable initially deferred for each row when (new.target_id='" + ticket + "') execute function fail_intervention_audit()");
        try {
            assertThatThrownBy(() -> interventions.apply(ticket,request)).isInstanceOf(RuntimeException.class);
            assertThat(read(ticket)).isEqualTo(initial);
            assertThat(jdbc.queryForMap("select * from saas_support_ticket where id=?",ticket)).isEqualTo(ticketBefore);
            assertThat(audits(ticket)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from saas_support_intervention_event where request_id=?",Long.class,request.requestId())).isZero();
        } finally {
            jdbc.execute("drop trigger fail_intervention_audit on saas_admin_audit_log");
            jdbc.execute("drop function fail_intervention_audit()");
        }
        State retried = submit(ticket,request,200);
        assertThat(retried.version()).isEqualTo(1); assertThat(audits(ticket)).isEqualTo(1);
    }

    @Test
    void getWaitingOnUncommittedWorkflowReturnsOneConsistentCommittedSnapshot() throws Exception {
        UUID ticket = linked().ticket(); Request request = request(read(ticket),"START_REMOTE","123456789");
        var waiting = new java.util.concurrent.atomic.AtomicReference<Future<State>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            State committed = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx -> {
                State staged = interventions.apply(ticket,request);
                int pid = jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                waiting.set(executor.submit(() -> read(ticket)));
                awaitBlocked(pid,waiting.get());
                return staged;
            });
            assertThat(waiting.get().get(15,TimeUnit.SECONDS)).isEqualTo(committed);
            assertThat(committed.events()).hasSize(1); assertThat(committed.ticketStatus()).isEqualTo("EN_CURSO");
        }
    }

    @Test
    void workflowWriteWaitingOnPlanningChangesDetectsChangedVersion() throws Exception {
        UUID ticket = linked().ticket(); State initial = read(ticket);
        var waiting = new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("select id from saas_support_ticket where id=? for update",UUID.class,ticket);
                int pid = jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                waiting.set(executor.submit(() -> responseCode(() -> interventions.apply(ticket,request(initial,"START_REMOTE",null)))));
                awaitBlocked(pid,waiting.get());
                State planned = interventions.apply(ticket, planning(initial, "Technician Ana"));
                interventions.apply(ticket, planning(planned, "Technician Bea"));
            });
            assertThat(waiting.get().get(15,TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(read(ticket).version()).isEqualTo(2); assertThat(read(ticket).events()).hasSize(2);
        assertThat(audits(ticket)).isEqualTo(2);
    }

    private long updateAudits(UUID ticket) {
        return jdbc.queryForObject("select count(*) from saas_admin_audit_log where action='UPDATE_SUPPORT_TICKET' and target_id=?",Long.class,ticket.toString());
    }
    private void awaitBlocked(int blockerPid, Future<?> waiting) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10); boolean blocked=false;
        while (!waiting.isDone() && System.nanoTime()<deadline) {
            blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blockerPid));
            if (blocked) break;
            try { Thread.sleep(20); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
        }
        assertThat(blocked).as("real request is waiting on transaction row lock").isTrue();
    }
    @Test
    void saasDiagnosisCanCloseOnlyAfterVerificationAndCanEscalate() throws Exception {
        Linked linked=linked(); UUID ticket=linked.ticket();
        State initial=read(ticket);
        State diagnosis=apply(ticket,initial,"START_SAAS",null);
        assertThat(diagnosis.status()).isEqualTo("SAAS_IN_PROGRESS");
        assertThat(diagnosis.assigneeUserId()).isNotNull();
        assertThat(diagnosis.assignee()).isEqualTo("admin");
        submit(ticket,request(diagnosis,"RESOLVE",null),409);
        State closed=close(ticket,diagnosis);
        assertThat(closed.failure().status()).isEqualTo("OPEN");
        assertThat(closed.ticketStatus()).isEqualTo("RESUELTO");
        var listed=support.supportTickets(linked.site().company().getId()).stream().filter(t->t.id().equals(ticket)).findFirst().orElseThrow();
        assertThat(listed.interventionStatus()).isEqualTo("RESOLVED");
        assertThat(listed.failureKey()).isEqualTo(linked.failure());
        assertThat(listed.failureStatus()).isEqualTo("OPEN");
        assertThat(listed.failureReceivedAt()).isNotNull();
        assertThat(listed.assigneeUserId()).isEqualTo(diagnosis.assigneeUserId());
        State restarted=apply(ticket,apply(ticket,closed,"REOPEN",null),"START_SAAS",null);
        assertThat(apply(ticket,restarted,"START_REMOTE",null).status()).isEqualTo("REMOTE_IN_PROGRESS");
        UUID other=linked().ticket();
        assertThat(apply(other,apply(other,read(other),"START_SAAS",null),"REQUIRE_ONSITE",null).status()).isEqualTo("ONSITE_REQUIRED");
    }

    @Test
    void waitsRequireReasonAndFutureReviewAndResumeSavedPhaseWithoutClosing() throws Exception {
        UUID ticket=linked().ticket(); State diagnosis=apply(ticket,read(ticket),"START_SAAS",null);
        submit(ticket,waitRequest(diagnosis,"WAIT_CUSTOMER",null),400);
        submit(ticket,waitRequest(diagnosis,"WAIT_CUSTOMER",Instant.now().minusSeconds(60)),400);
        Request wait=waitRequest(diagnosis,"WAIT_CUSTOMER",Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        State waiting=submit(ticket,wait,200);
        assertThat(waiting.status()).isEqualTo("WAITING_CUSTOMER"); assertThat(waiting.resumeStatus()).isEqualTo("SAAS_IN_PROGRESS");
        assertThat(waiting.nextReviewAt()).isEqualTo(wait.nextReviewAt());
        assertThat(submit(ticket,wait,200)).isEqualTo(waiting);
        submit(ticket,new Request(wait.requestId(),wait.expectedVersion(),wait.expectedTicketStatus(),wait.action(),wait.note(),null,null,null,null,null,null,null,wait.nextReviewAt().plusSeconds(60)),409);
        submit(ticket,request(waiting,"RESOLVE",null),409);
        submit(ticket,request(waiting,"REQUEST_VERIFICATION",null),409);
        State material=submit(ticket,waitRequest(waiting,"WAIT_MATERIAL",Instant.now().plusSeconds(14400)),200);
        assertThat(material.resumeStatus()).isEqualTo("SAAS_IN_PROGRESS");
        State planned=submit(ticket,planning(material,"Technician Ana"),200);
        assertThat(planned.nextReviewAt()).isEqualTo(material.nextReviewAt());
        State resumed=apply(ticket,planned,"RESUME",null);
        assertThat(resumed.status()).isEqualTo("SAAS_IN_PROGRESS"); assertThat(resumed.nextReviewAt()).isNull(); assertThat(resumed.resumeStatus()).isNull();
        submit(ticket,request(resumed,"RESUME",null),409);
        State verifying=apply(ticket,resumed,"REQUEST_VERIFICATION",null);
        State customer=submit(ticket,waitRequest(verifying,"WAIT_CUSTOMER",Instant.now().plusSeconds(7200)),200);
        assertThat(apply(ticket,customer,"RESUME",null).status()).isEqualTo("AWAITING_VERIFICATION");
        assertThat(read(ticket).resolutionSummary()).isEqualTo("Replaced faulty cable");
    }

    @Test
    void assignmentRequiresPermittedActiveUserAndCanExplicitlyUnassign() throws Exception {
        UUID ticket=linked().ticket(); State initial=read(ticket);
        UUID disabled=technicianId("Disabled "+UUID.randomUUID()), denied=technicianId("Denied "+UUID.randomUUID());
        jdbc.update("update saas_admin_user set active=false where id=?",disabled);
        jdbc.update("delete from saas_admin_user_role where user_id=?",denied);
        for (UUID invalid:List.of(UUID.randomUUID(),disabled,denied)) {
            submit(ticket,new Request(UUID.randomUUID(),0L,"ABIERTO","SAVE_DETAILS","Assign valid technician",null,null,null,null,null,null,invalid,null),400);
        }
        assertThat(read(ticket).assignees()).extracting(Assignee::id).doesNotContain(disabled,denied);
        submit(ticket,new Request(UUID.randomUUID(),0L,"ABIERTO","SAVE_DETAILS","Legacy free name",null,"Unverified technician",null,null,null,null),400);
        State planned=submit(ticket,planning(initial,"Technician Ana"),200);
        State unassigned=submit(ticket,new Request(UUID.randomUUID(),planned.version(),planned.ticketStatus(),"SAVE_DETAILS","Remove assignment",null,null,null,null,null,null,null,null),200);
        assertThat(unassigned.assigneeUserId()).isNull(); assertThat(unassigned.assignee()).isNull(); assertThat(unassigned.visitAt()).isNull();
        assertThat(unassigned.events().getFirst().assigneeUserId()).isEqualTo(technicianId("Technician Ana"));
        jdbc.update("update saas_support_intervention set assignee='Legacy Technician' where ticket_id=?",ticket);
        assertThat(read(ticket).assignee()).isEqualTo("Legacy Technician"); assertThat(read(ticket).assigneeUserId()).isNull();
    }

    @Test
    void urgentNotificationDisappearsAfterVerifiedClosureAndReturnsOnReopen() throws Exception {
        UUID ticket=linked().ticket(); support.updateSupportTicket(ticket,new UpdateSupportTicketRequest(null,"URGENTE"));
        String notification="ticket-urgent-"+ticket;
        assertThat(support.notifications()).extracting(AdminNotificationResponse::id).contains(notification);
        State closed=close(ticket,apply(ticket,read(ticket),"START_SAAS",null));
        assertThat(support.notifications()).extracting(AdminNotificationResponse::id).doesNotContain(notification);
        apply(ticket,closed,"REOPEN",null);
        assertThat(support.notifications()).extracting(AdminNotificationResponse::id).contains(notification);
        assertThat(support.status().expectedMigration()).isEqualTo("V74__support_diagnosis_waits_and_assignment");
    }

    @Test
    void replayOfCommittedV73PlanningKeepsHistoricalNameAndDoesNotRequireNewAssignment() throws Exception {
        UUID ticket=linked().ticket(); UUID key=UUID.fromString("d74629f5-648d-4a53-b46c-aabb11223344");
        Instant visit=Instant.parse("2026-10-02T10:00:00Z");
        jdbc.update("insert into saas_support_intervention(ticket_id,status,version,assignee,visit_at) values (?,'REMOTE_PENDING',1,'Legacy Ana',?)",ticket,Timestamp.from(visit));
        jdbc.update("""
                insert into saas_support_intervention_event(request_id,ticket_id,version,expected_ticket_status,action,status,note,actor,created_at,assignee,visit_at,request_fingerprint)
                values (?,?,1,'ABIERTO','SAVE_DETAILS','REMOTE_PENDING','Legacy technician planned','admin',now(),'Legacy Ana',?,?)
                """,key,ticket,Timestamp.from(visit),"cd60b396db9c8045be1ca102c44b873dc541e381c01e95061d049a00655aaef5");
        Request legacy=new Request(key,0L,"ABIERTO","SAVE_DETAILS","Legacy technician planned",null,"Legacy Ana",visit,null,null,null);
        State replay=submit(ticket,legacy,200);
        assertThat(replay.version()).isEqualTo(1); assertThat(replay.assignee()).isEqualTo("Legacy Ana"); assertThat(replay.assigneeUserId()).isNull();
        assertThat(replay.events()).hasSize(1); assertThat(audits(ticket)).isZero();
    }

    private Linked linked() {
        Site site = site(); String failure = failure(site, "LOCAL_APPLICATION", "OPEN", "DANGER");
        UUID ticket = repairs.manual(failure, new StoreRepairModels.ManualRequest("Manual investigation required")).ticketId();
        return new Linked(site, failure, ticket);
    }
    private record Linked(Site site, String failure, UUID ticket) { }
    private Request request(State state, String action, String teamViewer) {
        return new Request(UUID.randomUUID(), state.version(), state.ticketStatus(), action, "Investigation with controlled evidence", teamViewer,
                null, null, "REQUEST_VERIFICATION".equals(action) ? "Replaced faulty cable" : null,
                "RESOLVE".equals(action) ? "Test sale and printing succeeded" : null, "RESOLVE".equals(action) ? "Store manager" : null);
    }
    private Request planning(State state, String assignee) {
        return new Request(UUID.randomUUID(), state.version(), state.ticketStatus(), "SAVE_DETAILS", "Schedule technician visit", null,
                null, Instant.parse("2026-10-02T10:00:00Z"), null, null, null, technicianId(assignee), null);
    }
    private UUID technicianId(String username) {
        var existing=jdbc.query("select id from saas_admin_user where username=?",(rs,row)->rs.getObject(1,UUID.class),username);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("insert into saas_admin_user(id,username,password_hash,active,created_at,must_change_password) values (?,?,?,true,now(),false)",id,username,"test-only-no-login");
        jdbc.update("insert into saas_admin_user_role(user_id,role_id) select ?,role_id from saas_admin_role_permission where permission_code='MANAGE_SUPPORT_TICKETS' order by role_id limit 1",id);
        return id;
    }
    private Request waitRequest(State state, String action, Instant review) {
        return new Request(UUID.randomUUID(),state.version(),state.ticketStatus(),action,"Awaiting customer or spare part",null,null,null,null,null,null,null,review);
    }
    private State close(UUID ticket, State state) throws Exception {
        return apply(ticket, apply(ticket, state, "REQUEST_VERIFICATION", null), "RESOLVE", null);
    }
    private State apply(UUID ticket, State state, String action, String teamViewer) throws Exception { return submit(ticket, request(state,action,teamViewer),200); }
    private State read(UUID ticket) throws Exception { return mapper.readValue(http(get(path(ticket)),null,200),State.class); }
    private State submit(UUID ticket, Request body, int code) throws Exception {
        String json = http(post(path(ticket)),body,code); return code==200 ? mapper.readValue(json,State.class) : null;
    }
    private String http(MockHttpServletRequestBuilder request, Object body, int code) throws Exception {
        request.header("Authorization", basic("admin"));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();
    }
    private static String path(UUID ticket) { return "/api/v1/admin/tickets/" + ticket + "/interventions"; }
    private static UUID failureId(String failure) { return UUID.fromString(failure.substring(failure.indexOf(':')+1)); }
    private long audits(UUID ticket) { return jdbc.queryForObject("select count(*) from saas_admin_audit_log where target_id=? and action='SUPPORT_INTERVENTION'",Long.class,ticket.toString()); }
    private int responseCode(Runnable action) {
        try { action.run(); return 200; } catch (org.springframework.web.server.ResponseStatusException ex) { return ex.getStatusCode().value(); }
    }
    private void changeTicketStatus(UUID ticket, String next) {
        State snapshot = interventions.state(ticket);
        support.updateSupportTicket(ticket, new UpdateSupportTicketRequest(next, null, snapshot.version(), snapshot.ticketStatus()));
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

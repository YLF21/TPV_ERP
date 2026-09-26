package com.tpverp.backend.supervision.repair;

import static org.assertj.core.api.Assertions.*;

import com.tpverp.backend.supervision.StoreFailurePublisher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class StoreRemoteRepairPostgreSqlTest {
    private final String schema = "remote_repair_" + UUID.randomUUID().toString().replace("-", "");
    private final MutableClock clock = new MutableClock();
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private StoreRemoteRepairService service;
    private StoreRemoteRepairService.AuthorizedSite site;
    private UUID eventId;

    @BeforeEach void setup() {
        String url = System.getenv("TPV_ERP_TEST_DB_URL");
        String user = System.getenv("TPV_ERP_TEST_DB_USER");
        String password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("create schema " + schema);
        var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("""
                create table sync_outbox(event_id uuid primary key,empresa_id uuid not null,tienda_id uuid not null,
                    tipo_entidad varchar(64) not null,estado varchar(16) not null,version bigint not null,
                    intentos integer not null,proximo_intento_en timestamptz,reclamado_en timestamptz,claim_token uuid,
                    actualizado_en timestamptz not null,first_failure_at timestamptz,failure_count bigint not null,payload jsonb not null);
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V263__store_remote_repair_ledger.sql")).execute(dataSource);
        service = new StoreRemoteRepairService(jdbc, clock, transactions);
        site = new StoreRemoteRepairService.AuthorizedSite(new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(), UUID.randomUUID());
        jdbc.execute("""
                create table tienda(id uuid primary key,empresa_id uuid not null);
                create table licencia(id uuid primary key,tienda_id uuid not null references tienda(id),
                    instalacion_id uuid not null,activa boolean not null,import_metadata jsonb not null);
                create unique index active_license on licencia(tienda_id,instalacion_id) where activa;
                """);
        jdbc.update("insert into tienda values (?,?)", site.local().storeId(), site.local().companyId());
        jdbc.update("insert into licencia values (?,?,?,true,jsonb_build_object('saasCompanyId',?::text,'saasStoreId',?::text))",
                UUID.randomUUID(), site.local().storeId(), site.local().installationId(), site.saasCompanyId().toString(), site.saasStoreId().toString());
        eventId = UUID.randomUUID();
        jdbc.update("""
                insert into sync_outbox(event_id,empresa_id,tienda_id,tipo_entidad,estado,version,intentos,
                    actualizado_en,first_failure_at,failure_count,payload)
                values(?,?,?,'DOCUMENTO','DEAD_LETTER',7,5,?,?,2,'{"private":"never exposed"}')
                """, eventId, site.local().companyId(), site.local().storeId(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant().minusSeconds(60)));
    }

    @AfterEach void cleanup() { if (admin != null) admin.execute("drop schema if exists " + schema + " cascade"); }

    @Test void retryIsDurableAndIdempotentAcrossRestartAndOnlyActualDeliveryMeansSuccess() {
        var command = command();
        assertThat(accept(command)).extracting(RemoteRepairResult::status, RemoteRepairResult::resultCode)
                .containsExactly("RUNNING", "RETRY_QUEUED");
        assertThat(state()).isEqualTo("PENDIENTE");
        assertThat(version()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select intentos from sync_outbox", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select failure_count from sync_outbox", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select payload ->> 'private' from sync_outbox", String.class)).isEqualTo("never exposed");
        service = new StoreRemoteRepairService(jdbc, clock, transactions);
        assertThat(accept(command).status()).isEqualTo("RUNNING");
        assertThat(version()).isEqualTo(8);
        for (String state : List.of("ERROR", "ENVIANDO")) {
            jdbc.update("update sync_outbox set estado=?,version=version+1", state);
            assertThat(service.refresh(site.local().installationId(), command.commandId(), List.of(site)).status()).isEqualTo("RUNNING");
        }
        jdbc.update("update sync_outbox set estado='ENVIADO',version=version+1");
        var delivered = service.refresh(site.local().installationId(), command.commandId(), List.of(site));
        assertThat(delivered.status()).isEqualTo("SUCCEEDED");
        assertThat(delivered.resultCode()).isEqualTo("SYNC_DELIVERED");
        // Simulated HTTP outage: no acknowledgement. A restart must still resend the terminal result.
        service = new StoreRemoteRepairService(jdbc, clock, transactions);
        assertThat(service.pending(site.local().installationId())).containsExactly(command.commandId());
        assertThat(accept(command)).isEqualTo(delivered);
        service.acknowledge(delivered);
        assertThat(service.pending(site.local().installationId())).isEmpty();
    }

    @Test void failureAndExpiryAreTruthfulAndAnOldReceiptCannotAcknowledgeANewerOutcome() {
        var command = command();
        var queued = accept(command);
        service.acknowledge(queued);
        assertThat(service.pending(site.local().installationId())).contains(command.commandId());
        jdbc.update("update sync_outbox set estado='DEAD_LETTER',version=version+1");
        var failed = service.refresh(site.local().installationId(), command.commandId(), List.of(site));
        assertThat(failed.resultCode()).isEqualTo("RETRY_FAILED");
        service.acknowledge(queued);
        assertThat(service.pending(site.local().installationId())).contains(command.commandId());
        assertThat(accept(command)).isEqualTo(failed);
        assertThat(state()).isEqualTo("DEAD_LETTER");
        var next = new RemoteRepairCommand(UUID.randomUUID(), site.saasCompanyId(), site.saasStoreId(), "RETRY_SYNC_OUTBOX", eventId,
                version(), clock.instant().plusSeconds(20));
        assertThat(accept(next).status()).isEqualTo("RUNNING");
        clock.now = clock.now.plusSeconds(21);
        assertThat(service.refresh(site.local().installationId(), next.commandId(), List.of(site)).resultCode()).isEqualTo("REPAIR_EXPIRED");
        assertThat(state()).isEqualTo("PENDIENTE");
    }

    @Test void rejectsExpiredUnsupportedStaleAndSelfReportingRequestsWithoutMutatingTheEvent() {
        var expired = new RemoteRepairCommand(UUID.randomUUID(), site.saasCompanyId(), site.saasStoreId(), "RETRY_SYNC_OUTBOX", eventId, 7, clock.instant());
        assertThat(accept(expired).resultCode()).isEqualTo("REPAIR_EXPIRED");
        var unsupported = new RemoteRepairCommand(UUID.randomUUID(), site.saasCompanyId(), site.saasStoreId(), "EXECUTE_SQL", eventId, 7, clock.instant().plusSeconds(60));
        assertThat(accept(unsupported).resultCode()).isEqualTo("UNSUPPORTED_ACTION");
        var stale = new RemoteRepairCommand(UUID.randomUUID(), site.saasCompanyId(), site.saasStoreId(), "RETRY_SYNC_OUTBOX", eventId, 6, clock.instant().plusSeconds(60));
        assertThat(accept(stale).resultCode()).isEqualTo("STALE_EVENT");
        jdbc.update("update sync_outbox set tipo_entidad='STORE_FAILURE'");
        assertThat(accept(command()).resultCode()).isEqualTo("UNSUPPORTED_ACTION");
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
    }

    @Test void anotherManualRetryOrAlreadySentEventNeverGetsReopenedOrClaimedAsOurSuccess() {
        for (String status : List.of("PENDIENTE", "ERROR", "ENVIANDO", "ENVIADO")) {
            jdbc.update("update sync_outbox set estado=?", status);
            assertThat(accept(command()).resultCode()).isEqualTo("STALE_EVENT");
            assertThat(state()).isEqualTo(status); assertThat(version()).isEqualTo(7);
        }
    }

    @Test void centralAndLocalScopeMustBothMatchAndImmutableCommandCannotBeRebound() {
        var otherSite = new StoreRemoteRepairService.AuthorizedSite(
                new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), site.local().installationId()), UUID.randomUUID(), UUID.randomUUID());
        var crossStore = new RemoteRepairCommand(UUID.randomUUID(), otherSite.saasCompanyId(), otherSite.saasStoreId(),
                "RETRY_SYNC_OUTBOX", eventId, 7, clock.instant().plusSeconds(60));
        assertThat(service.accept(site.local().installationId(), List.of(site, otherSite), crossStore).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        var missing = new RemoteRepairCommand(UUID.randomUUID(), UUID.randomUUID(), site.saasStoreId(), "RETRY_SYNC_OUTBOX", eventId, 7, clock.instant().plusSeconds(60));
        assertThat(accept(missing).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        var command = command();
        assertThat(accept(command).status()).isEqualTo("RUNNING");
        var modified = new RemoteRepairCommand(command.commandId(), command.companyId(), command.storeId(), command.action(), command.eventId(), 8, command.expiresAt());
        assertThatThrownBy(() -> accept(modified)).hasMessage("REMOTE_REPAIR_IMMUTABLE_COMMAND_CONFLICT");
        assertThatThrownBy(() -> service.accept(UUID.randomUUID(), List.of(site), command)).hasMessage("REMOTE_REPAIR_IMMUTABLE_COMMAND_CONFLICT");
        assertThat(accept(command).status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select expected_version from store_remote_repair where command_id=?", Long.class, command.commandId())).isEqualTo(7);
        assertThat(version()).isEqualTo(8);
    }

    @Test void outboxMutationRollsBackIfLedgerResultCannotCommit() {
        jdbc.execute("""
                create function reject_repair_result() returns trigger language plpgsql as $$
                begin if new.status='RUNNING' then raise exception 'synthetic result failure'; end if; return new; end $$;
                create trigger reject_repair_result before update on store_remote_repair for each row execute function reject_repair_result();
                """);
        var command = command();
        assertThatThrownBy(() -> accept(command)).isInstanceOf(RuntimeException.class);
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from store_remote_repair", Long.class)).isZero();
        jdbc.execute("drop trigger reject_repair_result on store_remote_repair");
        assertThat(accept(command).status()).isEqualTo("RUNNING");
    }

    @Test void deadlineIsRecheckedImmediatelyBeforeExecutionAfterALockWait() {
        var advancing = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(advancing.instant()).thenReturn(clock.instant(), clock.instant().plusSeconds(901));
        service = new StoreRemoteRepairService(jdbc, advancing, transactions);
        assertThat(accept(command()).resultCode()).isEqualTo("REPAIR_EXPIRED");
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
    }

    @Test void actualDeliveryCanBeRecordedAfterDeadlineWithoutReExecutingAndDeletedEventsFailSafely() {
        var command = command();
        accept(command);
        jdbc.update("update sync_outbox set estado='ENVIADO',version=version+1");
        clock.now = clock.now.plusSeconds(901);
        assertThat(service.refresh(site.local().installationId(), command.commandId(), List.of(site)).resultCode()).isEqualTo("SYNC_DELIVERED");
        jdbc.update("update sync_outbox set estado='DEAD_LETTER',version=7");
        var missing = command();
        accept(missing);
        jdbc.update("delete from sync_outbox where event_id=?", eventId);
        assertThat(service.refresh(site.local().installationId(), missing.commandId(), List.of(site)).resultCode()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test void concurrentRedeliveryOnlyReopensOnce() throws Exception {
        var command = command();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> accept(command));
            var second = executor.submit(() -> accept(command));
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("RUNNING");
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo("RUNNING");
        }
        assertThat(version()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from store_remote_repair", Long.class)).isEqualTo(1);
    }

    @Test void staleSchedulerSnapshotCannotAuthorizeRevokedReboundOrReassignedLicense() {
        jdbc.update("update licencia set activa=false");
        assertThat(accept(command()).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        jdbc.update("update licencia set activa=true,import_metadata=jsonb_set(import_metadata,'{saasStoreId}',to_jsonb(?::text))", UUID.randomUUID().toString());
        assertThat(accept(command()).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        jdbc.update("update licencia set import_metadata=jsonb_set(import_metadata,'{saasStoreId}',to_jsonb(?::text)),instalacion_id=?",
                site.saasStoreId().toString(), UUID.randomUUID());
        assertThat(accept(command()).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        jdbc.update("update licencia set instalacion_id=?", site.local().installationId());
        jdbc.update("update tienda set empresa_id=?", UUID.randomUUID());
        assertThat(accept(command()).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
    }

    @Test void ambiguousSaasStoreMappingCannotSelectAnArbitraryLocalStore() {
        var duplicateScope = new StoreRemoteRepairService.AuthorizedSite(
                new StoreFailurePublisher.Site(site.local().companyId(), UUID.randomUUID(), site.local().installationId()),
                site.saasCompanyId(), site.saasStoreId());
        assertThat(service.accept(site.local().installationId(), List.of(site, duplicateScope), command()).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
    }

    @Test void committedRevocationDuringAuthorizationLockWaitPreventsExecution() throws Exception {
        var command = command();
        try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) { statement.executeUpdate("update licencia set activa=false"); }
            var result = executor.submit(() -> accept(command));
            awaitLicenseLockWait();
            blocker.commit();
            assertThat(result.get(8, TimeUnit.SECONDS).resultCode()).isEqualTo("EVENT_NOT_FOUND");
        }
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
    }

    @Test void expirationWhileWaitingForLicenseLockDoesNotExecute() throws Exception {
        var command = command();
        try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) { statement.executeQuery("select id from licencia for update").close(); }
            var result = executor.submit(() -> accept(command));
            awaitLicenseLockWait();
            clock.now = clock.now.plusSeconds(901);
            blocker.commit();
            assertThat(result.get(8, TimeUnit.SECONDS).resultCode()).isEqualTo("REPAIR_EXPIRED");
        }
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
    }

    @Test void authorizationLockWaitIsBoundedAndRollsBackTheIncompleteLedger() throws Exception {
        var command = command();
        try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) { statement.executeQuery("select id from licencia for update").close(); }
            var result = executor.submit(() -> accept(command));
            awaitLicenseLockWait();
            assertThatThrownBy(() -> result.get(8, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(org.springframework.dao.QueryTimeoutException.class);
            blocker.rollback();
        }
        assertThat(state()).isEqualTo("DEAD_LETTER"); assertThat(version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from store_remote_repair", Long.class)).isZero();
        assertThat(accept(command).status()).isEqualTo("RUNNING");
    }

    private void awaitLicenseLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from pg_stat_activity where wait_event_type='Lock'
                        and query like 'select l.id from licencia%' and pid<>pg_backend_pid())
                    """, Boolean.class))) return;
            Thread.sleep(20);
        }
        fail("Repair never reached the authorization row lock");
    }

    private RemoteRepairCommand command() { return new RemoteRepairCommand(UUID.randomUUID(), site.saasCompanyId(), site.saasStoreId(),
            "RETRY_SYNC_OUTBOX", eventId, 7, clock.instant().plusSeconds(900)); }
    private RemoteRepairResult accept(RemoteRepairCommand command) { return service.accept(site.local().installationId(), List.of(site), command); }
    private String state() { return jdbc.queryForObject("select estado from sync_outbox where event_id=?", String.class, eventId); }
    private long version() { return jdbc.queryForObject("select version from sync_outbox where event_id=?", Long.class, eventId); }
    private static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-22T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

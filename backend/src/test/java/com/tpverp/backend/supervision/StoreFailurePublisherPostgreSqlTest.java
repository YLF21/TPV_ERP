package com.tpverp.backend.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes the actual reporter SQL and additive migration against an isolated synthetic schema. */
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
class StoreFailurePublisherPostgreSqlTest {
    private final String schema = "store_report_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private StoreFailurePublisher publisher;
    private StoreFailurePublisher.Site site;
    private ApplicationFailureRecorder recorder;
    private LicenseRepository licenses;

    @BeforeEach
    void schema() throws Exception {
        String url = System.getenv("TPV_ERP_TEST_DB_URL");
        String user = System.getenv("TPV_ERP_TEST_DB_USER");
        String password = System.getenv("TPV_ERP_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("create schema " + schema);
        var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("""
                create table control_evento(id uuid primary key,tienda_id uuid,tipo text);
                create table control_alerta(id uuid primary key,evento_id uuid,tienda_id uuid,version bigint,
                    estado text,prioridad text,creada_en timestamptz,actualizada_en timestamptz);
                create table sync_outbox(id uuid primary key,event_id uuid,empresa_id uuid,tienda_id uuid,
                    tipo_entidad text,entidad_id uuid,payload jsonb,creado_en timestamptz,actualizado_en timestamptz,
                    estado text,version bigint,intentos integer,proximo_intento_en timestamptz,reclamado_en timestamptz,claim_token uuid);
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V251__store_failure_reporting.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V262__local_application_failures.sql")).execute(dataSource);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.enqueue(any())).thenAnswer(call -> {
            SyncOutboundEventCommand command = call.getArgument(0);
            jdbc.update("""
                    insert into sync_outbox(id,event_id,empresa_id,tienda_id,tipo_entidad,entidad_id,payload,creado_en,actualizado_en,estado,version,intentos)
                    values (?,?,?,?,?,?,?::jsonb,now(),now(),'PENDIENTE',0,0)
                    """, UUID.randomUUID(), UUID.randomUUID(), command.companyId(), command.storeId(), command.entityType(), command.entityId(),
                    mapper.writeValueAsString(command.payload()));
            return null;
        });
        licenses = mock(LicenseRepository.class);
        publisher = new StoreFailurePublisher(jdbc, licenses, outbox);
        site = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        recorder = new ApplicationFailureRecorder(jdbc, licenses, new DataSourceTransactionManager(dataSource),
                new org.springframework.mock.env.MockEnvironment().withProperty("tpv.verifactu.system-version", "4.2.0"), outbox);
        var license = mock(com.tpverp.backend.licensing.License.class);
        when(license.getLocalCompanyId()).thenReturn(site.companyId());
        when(license.getTiendaId()).thenReturn(site.storeId());
        when(license.getInstalacionId()).thenReturn(site.installationId());
        when(license.getSaasCompanyId()).thenReturn(UUID.randomUUID());
        when(license.getSaasStoreId()).thenReturn(UUID.randomUUID());
        when(licenses.findActiveByTiendaId(site.storeId())).thenReturn(java.util.List.of(license));
    }

    @AfterEach
    void cleanup() { if (admin != null) admin.execute("drop schema if exists " + schema + " cascade"); }

    @Test
    void committedAlertSnapshotsAreDeduplicatedReplayedAfterTransportExhaustionAndRemainReviewed() {
        UUID event = UUID.randomUUID(); UUID alert = UUID.randomUUID();
        jdbc.update("insert into control_evento values (?,?, 'CASH_SESSION_DISCREPANCY')", event, site.storeId());
        jdbc.update("insert into control_alerta values (?,?,?,0,'NEW','MEDIUM',now(),now())", alert, event, site.storeId());
        assertThat(publish()).isEqualTo(1);
        assertThat(publish()).isZero();
        // A replacement installation needs its own authenticated snapshot;
        // an old installation's delivered report must not suppress it.
        var replacement = new StoreFailurePublisher.Site(site.companyId(), site.storeId(), UUID.randomUUID());
        int replacementReports = transaction.execute(status -> publisher.publish(replacement));
        assertThat(replacementReports).isEqualTo(1);
        jdbc.update("update sync_outbox set estado = 'DEAD_LETTER' where entidad_id = ?", alert);
        assertThat(publish()).isEqualTo(1);
        jdbc.update("update control_alerta set estado='REVIEWED',version=1,actualizada_en=now() where id=?", alert);
        assertThat(publish()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload ->> 'status' from sync_outbox where entidad_id=? and payload ->> 'sourceRevision'='1'", String.class, alert))
                .isEqualTo("REVIEWED");
        assertThat(publish()).isZero();
    }

    @Test
    void deliveryThatAlreadyRecoveredIsStillReportedAndReporterFailuresDoNotRecursivelyReport() {
        UUID source = UUID.randomUUID(); Instant failure = Instant.now().minusSeconds(120);
        jdbc.update("""
                insert into sync_outbox(id,event_id,empresa_id,tienda_id,tipo_entidad,entidad_id,payload,creado_en,actualizado_en,
                    estado,version,intentos,first_failure_at,last_failure_at,failure_count)
                values (?,?,?,?, 'DOCUMENTO',?,'{"privateCustomer":"not exported"}',?,now(),'ENVIADO',4,2,?,?,1)
                """, UUID.randomUUID(), source, site.companyId(), site.storeId(), UUID.randomUUID(), Timestamp.from(failure.minusSeconds(10)),
                Timestamp.from(failure), Timestamp.from(failure));
        assertThat(publish()).isEqualTo(1);
        String snapshot = jdbc.queryForObject("select payload::text from sync_outbox where tipo_entidad='STORE_FAILURE'", String.class);
        assertThat(snapshot).contains("RESOLVED", "SYNC_DELIVERY_FAILED").doesNotContain("privateCustomer", "not exported");
        jdbc.update("update sync_outbox set estado='ERROR',failure_count=1,first_failure_at=now(),last_failure_at=now() where tipo_entidad='STORE_FAILURE'");
        assertThat(publish()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox where tipo_entidad='STORE_FAILURE'", Long.class)).isEqualTo(1);
    }

    @Test
    void boundedScanDrainsBacklogWithoutStarvingLaterAlertsOrCrossingStores() {
        for (int i = 0; i < 55; i++) {
            UUID event = UUID.randomUUID(); UUID alert = UUID.randomUUID();
            jdbc.update("insert into control_evento values (?,?,'TICKET_CANCELLED')", event, site.storeId());
            jdbc.update("insert into control_alerta values (?,?,?,0,'NEW','MEDIUM',now(),now())", alert, event, site.storeId());
        }
        UUID foreignStore = UUID.randomUUID(); UUID foreignEvent = UUID.randomUUID();
        jdbc.update("insert into control_evento values (?,?,'TICKET_CANCELLED')", foreignEvent, foreignStore);
        jdbc.update("insert into control_alerta values (?,?,?,0,'NEW','MEDIUM',now(),now())", UUID.randomUUID(), foreignEvent, foreignStore);
        assertThat(publish()).isEqualTo(50); assertThat(publish()).isEqualTo(5); assertThat(publish()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox", Long.class)).isEqualTo(55);
    }

    @Test
    void applicationFailureSurvivesBusinessRollbackAndRetainsEveryTraceAcrossOfflineRetries() throws Exception {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("synthetic", null, java.util.List.of());
        auth.setDetails(new com.tpverp.backend.security.domain.OperationalSessionContext(UUID.randomUUID(), site.storeId()));
        var failure = new NullPointerException("private customer token SQL must never leave this process");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.tpverp.backend.document.SaleService", "save", "Secret.java", 42)});
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        String firstTrace = "web-first-customer-reference";
        request.setAttribute(com.tpverp.backend.shared.api.CorrelationIdFilter.ATTRIBUTE, firstTrace);
        transaction.executeWithoutResult(status -> {
            recorder.record(auth, ApplicationFailureRecorder.Module.SALES, failure, request);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from local_application_failure", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox", Long.class)).isEqualTo(1);
        String trace = UUID.randomUUID().toString();
        request.setAttribute(com.tpverp.backend.shared.api.CorrelationIdFilter.ATTRIBUTE, trace);
        recorder.record(auth, ApplicationFailureRecorder.Module.SALES, failure, request);
        assertThat(jdbc.queryForObject("select occurrences from local_application_failure", Long.class)).isEqualTo(2);
        assertThat(publish()).isZero();
        assertThat(jdbc.queryForList("select payload ->> 'traceId' from sync_outbox order by (payload ->> 'sourceRevision')::bigint", String.class))
                .containsExactly(firstTrace, trace);
        String payload = jdbc.queryForObject("select payload::text from sync_outbox where payload ->> 'sourceRevision'='1'", String.class);
        var evidence = mapper.readTree(payload);
        assertThat(evidence.get("schemaVersion").intValue()).isEqualTo(2);
        assertThat(evidence.get("source").textValue()).isEqualTo("LOCAL_APPLICATION");
        assertThat(evidence.get("status").textValue()).isEqualTo("OPEN");
        assertThat(evidence.get("occurrences").longValue()).isEqualTo(2);
        assertThat(evidence.get("traceId").textValue()).isEqualTo(trace);
        assertThat(evidence.get("errorLocation").textValue()).isEqualTo("com.tpverp.backend.document.SaleService.save:42");
        assertThat(payload).doesNotContain("private", "customer", "token", "SQL", "Secret.java");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/phase1-application-payload.json"), payload);
        var eventIds = jdbc.queryForList("select event_id from sync_outbox order by event_id", UUID.class);
        jdbc.update("update sync_outbox set estado='DEAD_LETTER',intentos=10");
        assertThat(publish()).isEqualTo(2);
        assertThat(publish()).isZero();
        assertThat(jdbc.queryForList("select event_id from sync_outbox order by event_id", UUID.class)).isEqualTo(eventIds);
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox where estado='PENDIENTE' and intentos=0", Long.class)).isEqualTo(2);
        assertThat(transaction.<Integer>execute(status -> publisher.publish(new StoreFailurePublisher.Site(site.companyId(), site.storeId(), UUID.randomUUID())))).isZero();
        assertThat(transaction.<Integer>execute(status -> publisher.publish(new StoreFailurePublisher.Site(UUID.randomUUID(), site.storeId(), site.installationId())))).isZero();
        recorder.record(auth, ApplicationFailureRecorder.Module.SALES, failure, request);
        assertThat(publish()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from sync_outbox where payload ->> 'status'='RESOLVED'", Long.class)).isZero();
    }

    @Test
    void nullableDiagnosticsRemainInV2AndUnknownStoresAreNotCaptured() {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("synthetic", null, java.util.List.of());
        auth.setDetails(new com.tpverp.backend.security.domain.OperationalSessionContext(UUID.randomUUID(), UUID.randomUUID()));
        recorder.record(auth, ApplicationFailureRecorder.Module.APPLICATION, new RuntimeException(), null);
        assertThat(jdbc.queryForObject("select count(*) from local_application_failure", Long.class)).isZero();
        transaction.executeWithoutResult(status -> recorder.persist(site,
                new ApplicationFailureRecorder.Evidence(ApplicationFailureRecorder.Module.APPLICATION, null, UUID.randomUUID().toString(), null, null)));
        assertThat(publish()).isZero();
        assertThat(jdbc.queryForObject("select payload ?& array['module','appVersion','traceId','exceptionType','errorLocation'] from sync_outbox", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("select payload ->> 'appVersion' from sync_outbox", String.class)).isNull();
    }

    private int publish() { return transaction.execute(status -> publisher.publish(site)); }
}

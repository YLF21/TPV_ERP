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
                    estado text,version bigint,intentos integer);
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V251__store_failure_reporting.sql")).execute(dataSource);
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
        publisher = new StoreFailurePublisher(jdbc, mock(LicenseRepository.class), outbox);
        site = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
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

    private int publish() { return transaction.execute(status -> publisher.publish(site)); }
}

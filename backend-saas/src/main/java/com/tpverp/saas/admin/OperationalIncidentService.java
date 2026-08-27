package com.tpverp.saas.admin;

import com.tpverp.saas.admin.OperationalIncidentModels.CancelMemberCategoryBootstrapRequest;
import com.tpverp.saas.admin.OperationalIncidentModels.CancellationResult;
import com.tpverp.saas.admin.OperationalIncidentModels.Incident;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OperationalIncidentService {
    static final String CATEGORY_BOOTSTRAP_INCIDENT = "MEMBER_CATEGORY_BOOTSTRAP_STALLED";
    static final String CANCEL_CATEGORY_BOOTSTRAP = "CANCEL_MEMBER_CATEGORY_BOOTSTRAP";
    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;
    private final Clock clock;
    private final Duration bootstrapInactivity;

    public OperationalIncidentService(
            JdbcTemplate jdbc,
            AdminAuditService audit,
            Clock clock,
            @Value("${tpv.saas.operational-incidents.category-bootstrap-inactivity:PT1H}")
            Duration bootstrapInactivity) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.bootstrapInactivity = bootstrapInactivity;
        if (bootstrapInactivity == null || bootstrapInactivity.isNegative() || bootstrapInactivity.isZero()) {
            throw new IllegalArgumentException("La inactividad del bootstrap debe ser positiva");
        }
    }

    @Transactional(readOnly = true)
    public List<Incident> list(UUID companyId) {
        String companyFilter = companyId == null ? "" : " and bootstrap.company_id=?";
        Instant cutoff = clock.instant().minus(bootstrapInactivity);
        String sql = """
                select bootstrap.id,
                       bootstrap.company_id,
                       bootstrap.status,
                       bootstrap.expected_store_count,
                       bootstrap.conflict_reason,
                       bootstrap.created_at,
                       bootstrap.last_activity_at,
                       (select count(*) from saas_member_category_bootstrap_store store
                         where store.bootstrap_id=bootstrap.id and store.completed_at is not null) completed_store_count,
                       (select count(*) from saas_member_category_bootstrap_snapshot snapshot
                         where snapshot.bootstrap_id=bootstrap.id) snapshot_count,
                       (select count(*) from saas_member_category_bootstrap_chunk chunk
                         join saas_member_category_bootstrap_snapshot snapshot
                           on snapshot.snapshot_id=chunk.snapshot_id
                         where snapshot.bootstrap_id=bootstrap.id) chunk_count,
                       (select baseline.id from saas_member_category_bootstrap baseline
                         where baseline.company_id=bootstrap.company_id
                           and baseline.status='COMPLETED'
                         order by baseline.completed_at desc nulls last, baseline.created_at desc
                         limit 1) completed_baseline_id
                from saas_member_category_bootstrap bootstrap
                where bootstrap.status in ('COLLECTING','CONFLICT')
                  and bootstrap.last_activity_at <= ?
                """ + companyFilter + " order by bootstrap.last_activity_at asc limit 100";
        return companyId == null
                ? jdbc.query(sql, (resultSet, rowNumber) -> incident(resultSet), timestamp(cutoff))
                : jdbc.query(sql, (resultSet, rowNumber) -> incident(resultSet), timestamp(cutoff), companyId);
    }

    @Transactional
    public CancellationResult cancelMemberCategoryBootstrap(
            UUID companyId,
            UUID bootstrapId,
            CancelMemberCategoryBootstrapRequest request) {
        ValidatedCommand command = validate(companyId, bootstrapId, request);
        jdbc.query(
                "select pg_advisory_xact_lock(hashtext(?))",
                (resultSet, rowNumber) -> Boolean.TRUE,
                command.commandId().toString());

        StoredCommand stored = storedCommand(command.commandId());
        if (stored != null) {
            if (!stored.matches(command)) {
                throw conflict("commandId ya fue utilizado con otra solicitud");
            }
            return stored.replay();
        }

        Bootstrap bootstrap = lockBootstrap(command.bootstrapId());
        if (bootstrap == null || !bootstrap.companyId().equals(command.companyId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bootstrap de categorias no encontrado");
        }
        if (!bootstrap.status().equals(command.expectedStatus())) {
            throw conflict("El estado actual no coincide con expectedStatus");
        }
        if (!"COLLECTING".equals(bootstrap.status()) && !"CONFLICT".equals(bootstrap.status())) {
            throw conflict("Solo se puede cancelar un bootstrap COLLECTING o CONFLICT");
        }
        UUID completedBaselineId = completedBaseline(command.companyId());
        if (completedBaselineId == null) {
            throw conflict("No existe un baseline COMPLETED que permita cancelar el bootstrap residual");
        }

        Counts counts = counts(command.bootstrapId());
        if (counts.snapshotCount() != 0 || counts.chunkCount() != 0) {
            throw conflict("El bootstrap contiene snapshots o chunks y requiere conciliacion manual");
        }
        Instant now = clock.instant();
        if (bootstrap.lastActivityAt().isAfter(now.minus(bootstrapInactivity))) {
            throw conflict("El bootstrap sigue activo dentro de la ventana de inactividad");
        }

        int updated = jdbc.update("""
                update saas_member_category_bootstrap
                set status='CANCELLED', cancelled_at=?
                where id=? and company_id=? and status=?
                """, timestamp(now), command.bootstrapId(), command.companyId(), command.expectedStatus());
        if (updated != 1) {
            throw conflict("El bootstrap cambio mientras se intentaba cancelar");
        }

        String username = audit.currentUsername();
        jdbc.update("""
                insert into saas_operational_incident_command (
                    command_id, command_type, company_id, target_id,
                    expected_status, reason, requested_by, requested_at,
                    result_status, completed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'CANCELLED', ?)
                """, command.commandId(), CANCEL_CATEGORY_BOOTSTRAP,
                command.companyId(), command.bootstrapId(), command.expectedStatus(),
                command.reason(), username, timestamp(now), timestamp(now));
        audit.log(
                "CANCEL_MEMBER_CATEGORY_BOOTSTRAP_INCIDENT",
                "MEMBER_CATEGORY_BOOTSTRAP",
                command.bootstrapId().toString(),
                "commandId=" + command.commandId()
                        + "; companyId=" + command.companyId()
                        + "; expectedStatus=" + command.expectedStatus()
                        + "; baselineId=" + completedBaselineId
                        + "; reason=" + command.reason());
        return new CancellationResult(
                command.commandId(), command.companyId(), command.bootstrapId(),
                command.expectedStatus(), "CANCELLED", now, false);
    }

    private Incident incident(ResultSet resultSet) throws SQLException {
        int snapshotCount = resultSet.getInt("snapshot_count");
        int chunkCount = resultSet.getInt("chunk_count");
        Instant lastActivityAt = instant(resultSet, "last_activity_at");
        UUID baselineId = resultSet.getObject("completed_baseline_id", UUID.class);
        return new Incident(
                CATEGORY_BOOTSTRAP_INCIDENT,
                resultSet.getObject("company_id", UUID.class),
                resultSet.getObject("id", UUID.class),
                resultSet.getString("status"),
                resultSet.getInt("expected_store_count"),
                resultSet.getInt("completed_store_count"),
                snapshotCount,
                chunkCount,
                summarize(resultSet.getString("conflict_reason")),
                instant(resultSet, "created_at"),
                lastActivityAt,
                true,
                baselineId != null && snapshotCount == 0 && chunkCount == 0,
                baselineId);
    }

    private StoredCommand storedCommand(UUID commandId) {
        List<StoredCommand> commands = jdbc.query("""
                select command_id, command_type, company_id, target_id,
                       expected_status, reason, result_status, completed_at
                from saas_operational_incident_command
                where command_id=?
                """, (resultSet, rowNumber) -> new StoredCommand(
                        resultSet.getObject("command_id", UUID.class),
                        resultSet.getString("command_type"),
                        resultSet.getObject("company_id", UUID.class),
                        resultSet.getObject("target_id", UUID.class),
                        resultSet.getString("expected_status"),
                        resultSet.getString("reason"),
                        resultSet.getString("result_status"),
                        instant(resultSet, "completed_at")), commandId);
        return commands.isEmpty() ? null : commands.getFirst();
    }

    private Bootstrap lockBootstrap(UUID bootstrapId) {
        List<Bootstrap> bootstraps = jdbc.query("""
                select id, company_id, status, last_activity_at
                from saas_member_category_bootstrap
                where id=?
                for update
                """, (resultSet, rowNumber) -> new Bootstrap(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("company_id", UUID.class),
                        resultSet.getString("status"),
                        instant(resultSet, "last_activity_at")), bootstrapId);
        return bootstraps.isEmpty() ? null : bootstraps.getFirst();
    }

    private UUID completedBaseline(UUID companyId) {
        List<UUID> baselines = jdbc.query("""
                select id
                from saas_member_category_bootstrap
                where company_id=? and status='COMPLETED'
                order by completed_at desc nulls last, created_at desc
                limit 1
                """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), companyId);
        return baselines.isEmpty() ? null : baselines.getFirst();
    }

    private Counts counts(UUID bootstrapId) {
        return jdbc.queryForObject("""
                select
                    (select count(*) from saas_member_category_bootstrap_snapshot
                     where bootstrap_id=?) snapshot_count,
                    (select count(*) from saas_member_category_bootstrap_chunk chunk
                     join saas_member_category_bootstrap_snapshot snapshot
                       on snapshot.snapshot_id=chunk.snapshot_id
                     where snapshot.bootstrap_id=?) chunk_count
                """, (resultSet, rowNumber) -> new Counts(
                        resultSet.getInt("snapshot_count"),
                        resultSet.getInt("chunk_count")), bootstrapId, bootstrapId);
    }

    private static ValidatedCommand validate(
            UUID companyId,
            UUID bootstrapId,
            CancelMemberCategoryBootstrapRequest request) {
        if (companyId == null || bootstrapId == null || request == null || request.commandId() == null) {
            throw invalid("companyId, bootstrapId y commandId son obligatorios");
        }
        String expectedStatus = normalize(request.expectedStatus());
        if (!"COLLECTING".equals(expectedStatus) && !"CONFLICT".equals(expectedStatus)) {
            throw invalid("expectedStatus debe ser COLLECTING o CONFLICT");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 5 || reason.length() > MAX_REASON_LENGTH) {
            throw invalid("reason debe contener entre 5 y 1000 caracteres");
        }
        return new ValidatedCommand(request.commandId(), companyId, bootstrapId, expectedStatus, reason);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String summarize(String value) {
        if (value == null || value.length() <= MAX_SUMMARY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SUMMARY_LENGTH);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record ValidatedCommand(
            UUID commandId,
            UUID companyId,
            UUID bootstrapId,
            String expectedStatus,
            String reason) {
    }

    private record Bootstrap(UUID id, UUID companyId, String status, Instant lastActivityAt) {
    }

    private record Counts(int snapshotCount, int chunkCount) {
    }

    private record StoredCommand(
            UUID commandId,
            String commandType,
            UUID companyId,
            UUID targetId,
            String expectedStatus,
            String reason,
            String resultStatus,
            Instant completedAt) {

        boolean matches(ValidatedCommand command) {
            return CANCEL_CATEGORY_BOOTSTRAP.equals(commandType)
                    && companyId.equals(command.companyId())
                    && targetId.equals(command.bootstrapId())
                    && expectedStatus.equals(command.expectedStatus())
                    && reason.equals(command.reason());
        }

        CancellationResult replay() {
            return new CancellationResult(
                    commandId, companyId, targetId, expectedStatus,
                    resultStatus, completedAt, true);
        }
    }
}

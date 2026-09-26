package com.tpverp.saas.supervision;

import static com.tpverp.saas.supervision.StoreRepairModels.*;
import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.admin.AdminService;
import com.tpverp.saas.admin.CreateSupportTicketRequest;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoreRepairService {
    private static final String ACTION = "RETRY_SYNC_OUTBOX";
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final Set<String> FAILURE_CODES = Set.of("STALE_EVENT", "EVENT_NOT_FOUND", "UNSUPPORTED_ACTION", "RETRY_FAILED", "REPAIR_EXPIRED");
    private final jakarta.persistence.EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final StoreFailureQueryService failures;
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final AdminAuditService audit;
    private final AdminService admin;

    public StoreRepairService(jakarta.persistence.EntityManager entityManager, JdbcTemplate jdbc, Clock clock, StoreFailureQueryService failures,
            SaasInstallationRepository installations, InstallationAuthenticator authenticator,
            AdminAuditService audit, AdminService admin) {
        this.entityManager = entityManager; this.jdbc = jdbc; this.clock = clock; this.failures = failures; this.installations = installations;
        this.authenticator = authenticator; this.audit = audit; this.admin = admin;
    }

    @Transactional
    public RepairState state(String key) {
        Failure failure = failure(key);
        if (failure.installationId() != null) {
            lockInstallation(failure.installationId());
            expire(failure.installationId());
            failure = failure(key);
        }
        List<RepairCommandView> commands = commands(key);
        String reason = eligibility(failure);
        if (reason == null && commands.stream().anyMatch(command -> active(command.status()))) {
            reason = "Ya hay una reparacion pendiente para este fallo.";
        }
        return new RepairState(reason == null, reason, commands, manualTicket(key));
    }

    @Transactional
    public RepairCommandView create(String key, CreateRepairRequest request) {
        String reason = reason(request.reason());
        if (request.requestId() == null) throw badRequest("requestId obligatorio");
        // Global request lock prevents reuse across failure/company boundaries.
        lock("store-repair-request:" + request.requestId());
        Failure failure = failure(key);
        var previous = jdbc.query("select * from saas_store_repair_command where request_id = ?", this::saved, request.requestId());
        if (!previous.isEmpty()) {
            Saved value = previous.getFirst();
            if (!value.failureKey().equals(key) || !value.view().reason().equals(reason)
                    || !value.view().requestedBy().equals(audit.currentUsername())) {
                throw conflict("requestId ya utilizado para otra reparacion");
            }
            lockInstallation(value.installationId());
            expire(value.installationId());
            return command(value.view().commandId()).view();
        }
        String ineligible = eligibility(failure);
        if (ineligible != null) throw conflict(ineligible);
        lockInstallation(failure.installationId());
        expire(failure.installationId());
        failure = failure(key);
        ineligible = eligibility(failure);
        if (ineligible != null) throw conflict(ineligible);
        if (commands(key).stream().anyMatch(command -> active(command.status()))) {
            throw conflict("Ya hay una reparacion pendiente para este fallo");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                insert into saas_store_repair_command(command_id,request_id,failure_id,failure_key,company_id,store_id,
                    installation_id,action,event_id,expected_version,status,requested_by,reason,created_at,expires_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?,?,?)
                """, id, request.requestId(), failure.id(), key, failure.view().companyId(), failure.view().storeId(),
                failure.installationId(), ACTION, failure.view().sourceId(), failure.revision(), audit.currentUsername(), reason,
                timestamp(now), timestamp(now.plus(LIFETIME)), timestamp(now));
        audit.log("CREATE_STORE_REPAIR", "STORE_REPAIR_COMMAND", id.toString(), "failure=" + key + "; reason=" + reason);
        return command(id).view();
    }

    @Transactional
    public ManualResponse manual(String key, ManualRequest request) {
        String reason = reason(request.reason());
        Failure failure = failure(key);
        if (failure.view().companyId() == null) throw conflict("El fallo no tiene una empresa para crear un ticket");
        lock("store-failure-manual:" + key);
        UUID existing = manualTicket(key);
        if (existing != null) return new ManualResponse(existing);
        if (failure.installationId() != null) {
            lockInstallation(failure.installationId());
            expire(failure.installationId());
        }
        var recent = commands(key);
        String repairContext = recent.isEmpty() ? "Sin reparacion remota previa." : "Reparacion: " + recent.getFirst().commandId()
                + "; estado=" + recent.getFirst().status() + "; resultado=" + recent.getFirst().resultCode();
        var ticket = admin.createSupportTicket(failure.view().companyId(), new CreateSupportTicketRequest(
                "Seguimiento de fallo: " + failure.view().code(),
                "Fallo: " + key + "\nTienda: " + failure.view().storeId() + "\nInstalacion: " + failure.view().installationId()
                        + "\nDiagnostico: " + failure.view().traceId() + "\n" + repairContext + "\nMotivo: " + reason, "ALTA"));
        jdbc.update("""
                insert into saas_store_failure_manual(failure_key,company_id,ticket_id,requested_by,reason,created_at)
                values (?,?,?,?,?,?)
                """, key, failure.view().companyId(), ticket.id(), audit.currentUsername(), reason, timestamp(clock.instant()));
        audit.log("ESCALATE_STORE_FAILURE", "SUPPORT_TICKET", ticket.id().toString(), "failure=" + key + "; reason=" + reason);
        return new ManualResponse(ticket.id());
    }

    @Transactional
    public List<ClaimedCommand> claim(UUID publicInstallationId, String token) {
        SaasInstallation installation = authenticate(publicInstallationId, token);
        lockInstallation(installation.getId());
        expire(installation.getId());
        var pending = jdbc.query("""
                select * from saas_store_repair_command
                 where installation_id = ? and company_id = ? and store_id = ? and status in ('QUEUED','RUNNING')
                 order by case when status = 'QUEUED' then 0 else 1 end, created_at, command_id limit 10
                """, this::saved, installation.getId(), installation.getCompany().getId(), installation.getStore().getId());
        for (Saved command : pending) {
            if ("QUEUED".equals(command.view().status())) {
                jdbc.update("update saas_store_repair_command set status = 'RUNNING', updated_at = ? where command_id = ?",
                        timestamp(clock.instant()), command.view().commandId());
                audit.log("CLAIM_STORE_REPAIR", "STORE_REPAIR_COMMAND", command.view().commandId().toString(),
                        "installationId=" + installation.getInstallationId());
            }
        }
        return pending.stream().map(value -> new ClaimedCommand(value.view().commandId(), value.view().action(),
                value.view().companyId(), value.view().storeId(), value.view().eventId(), value.view().expectedVersion(),
                value.view().expiresAt())).toList();
    }

    @Transactional
    public RepairCommandView result(UUID id, ResultRequest request, String token) {
        SaasInstallation installation = authenticate(request.installationId(), token);
        validateResult(request);
        lockInstallation(installation.getId());
        Saved saved = command(id);
        if (!saved.installationId().equals(installation.getId())
                || !saved.view().companyId().equals(installation.getCompany().getId())
                || !saved.view().storeId().equals(installation.getStore().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reparacion no encontrada");
        }
        expire(installation.getId());
        RepairCommandView current = command(id).view();
        // Persist expiration even for late replies. A delayed success is never accepted as success.
        if ("EXPIRED".equals(current.status())) return current;
        if (Objects.equals(current.status(), request.status()) && Objects.equals(current.resultCode(), request.resultCode())) return current;
        if (!"RUNNING".equals(current.status())) throw conflict("La reparacion no admite este resultado");
        jdbc.update("update saas_store_repair_command set status = ?, result_code = ?, updated_at = ? where command_id = ?",
                request.status(), request.resultCode(), timestamp(clock.instant()), id);
        audit.log("RESULT_STORE_REPAIR", "STORE_REPAIR_COMMAND", id.toString(),
                "installationId=" + installation.getInstallationId() + "; status=" + request.status() + "; resultCode=" + request.resultCode());
        return command(id).view();
    }

    private SaasInstallation authenticate(UUID publicId, String token) {
        if (publicId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Instalacion no autorizada");
        SaasInstallation installation = installations.findByInstallationId(publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Instalacion no autorizada"));
        authenticator.requireToken(installation, token);
        lockInstallation(installation.getId());
        // Waiting for another command must not retain an old token, active flag or site mapping.
        entityManager.refresh(installation);
        authenticator.requireToken(installation, token);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select active from saas_store where id = ? for share", Boolean.class, installation.getStore().getId())))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tienda no activa");
        return installation;
    }

    private Failure failure(String key) {
        StoreFailureView view = failures.detail(key);
        UUID id = UUID.fromString(key.substring(key.indexOf(':') + 1));
        return jdbc.query("""
                select f.id, f.installation_id, f.source_revision, coalesce(i.active and i.company_id = f.company_id and i.store_id = f.store_id,false) as installation_active,
                       coalesce(s.active,false) as store_active
                  from saas_store_failure f left join saas_installation i on i.id = f.installation_id
                  left join saas_store s on s.id = f.store_id where f.id = ? and f.source = ?
                """, (rs, row) -> new Failure(view, id, rs.getObject("installation_id", UUID.class), rs.getLong("source_revision"),
                        rs.getBoolean("installation_active"), rs.getBoolean("store_active")), id, view.source()).stream().findFirst()
                .orElse(new Failure(view, null, null, 0, false, false));
    }

    private String eligibility(Failure failure) {
        if (!"LOCAL_SYNC".equals(failure.view().source()) || !"SYNC_DELIVERY_FAILED".equals(failure.view().code()))
            return "Este fallo requiere revision manual; no admite reintento de sincronizacion.";
        if (!"OPEN".equals(failure.view().status()) || !"DANGER".equals(failure.view().severity()))
            return "Solo se pueden reintentar fallos abiertos en DEAD_LETTER.";
        if (failure.installationId() == null || !failure.installationActive() || !failure.storeActive())
            return "La instalacion o la tienda no esta activa.";
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from saas_store_repair_command where failure_id = ? and status = 'SUCCEEDED' and expected_version >= ?)",
                Boolean.class, failure.id(), failure.revision())))
            return "La reparacion ya termino; espere un nuevo diagnostico de la tienda.";
        return null;
    }

    private List<RepairCommandView> commands(String key) {
        return jdbc.query("select * from saas_store_repair_command where failure_key = ? order by created_at desc, command_id desc limit 50",
                (rs, row) -> view(rs), key);
    }

    private UUID manualTicket(String key) {
        return jdbc.queryForList("select ticket_id from saas_store_failure_manual where failure_key = ?", UUID.class, key)
                .stream().findFirst().orElse(null);
    }

    private Saved command(UUID id) {
        return jdbc.query("select * from saas_store_repair_command where command_id = ?", this::saved, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reparacion no encontrada"));
    }

    private Saved saved(ResultSet rs, int row) throws SQLException {
        return new Saved(rs.getString("failure_key"), rs.getObject("installation_id", UUID.class), view(rs));
    }

    private RepairCommandView view(ResultSet rs) throws SQLException {
        return new RepairCommandView(rs.getObject("command_id", UUID.class), rs.getObject("request_id", UUID.class), rs.getString("action"),
                rs.getObject("company_id", UUID.class), rs.getObject("store_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getLong("expected_version"), rs.getString("status"), rs.getString("result_code"), rs.getString("requested_by"),
                rs.getString("reason"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private void expire(UUID installationId) {
        List<UUID> expired = jdbc.queryForList("""
                update saas_store_repair_command set status = 'EXPIRED', result_code = 'REPAIR_EXPIRED', updated_at = ?
                 where installation_id = ? and status in ('QUEUED','RUNNING') and expires_at <= ? returning command_id
                """, UUID.class, timestamp(clock.instant()), installationId, timestamp(clock.instant()));
        for (UUID id : expired) audit.log("EXPIRE_STORE_REPAIR", "STORE_REPAIR_COMMAND", id.toString(), "resultCode=REPAIR_EXPIRED");
    }

    private void lockInstallation(UUID id) {
        lock("store-repair-installation:" + id);
        // Same row used by revocation, token rotation and failure projection; keep one consistent lock order.
        jdbc.queryForObject("select id from saas_installation where id = ? for share", UUID.class, id);
        jdbc.queryForObject("select s.id from saas_store s join saas_installation i on i.store_id = s.id where i.id = ? for share of s", UUID.class, id);
    }
    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, row) -> 0, key);
    }
    private static boolean active(String status) { return "QUEUED".equals(status) || "RUNNING".equals(status); }
    private static void validateResult(ResultRequest request) {
        if (!("RUNNING".equals(request.status()) && "RETRY_QUEUED".equals(request.resultCode())
                || "SUCCEEDED".equals(request.status()) && "SYNC_DELIVERED".equals(request.resultCode())
                || "FAILED".equals(request.status()) && request.resultCode() != null && FAILURE_CODES.contains(request.resultCode()))) {
            throw badRequest("Resultado de reparacion no soportado");
        }
    }
    private static String reason(String value) {
        if (value == null || value.trim().length() < 5 || value.trim().length() > 500) throw badRequest("El motivo debe tener entre 5 y 500 caracteres");
        return value.trim();
    }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Failure(StoreFailureView view, UUID id, UUID installationId, long revision, boolean installationActive, boolean storeActive) { }
    private record Saved(String failureKey, UUID installationId, RepairCommandView view) { }
}

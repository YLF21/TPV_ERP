package com.tpverp.saas.supervision;

import static com.tpverp.saas.supervision.SupportInterventionModels.*;
import com.tpverp.saas.admin.AdminAuditService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SupportInterventionService {
    private static final Set<String> ACTIONS = Set.of("START_REMOTE", "REQUIRE_ONSITE", "START_ONSITE", "REQUEST_VERIFICATION", "VERIFICATION_FAILED", "RESOLVE", "REOPEN", "SAVE_DETAILS", "START_SAAS", "WAIT_CUSTOMER", "WAIT_MATERIAL", "RESUME");
    private static final Set<String> ACTIVE = Set.of("REMOTE_PENDING","SAAS_IN_PROGRESS","REMOTE_IN_PROGRESS","ONSITE_REQUIRED","ONSITE_IN_PROGRESS","AWAITING_VERIFICATION");
    private static final Set<String> WAITING = Set.of("WAITING_CUSTOMER","WAITING_MATERIAL");
    private static final Set<String> TICKET_STATUSES = Set.of("ABIERTO", "EN_CURSO", "RESUELTO");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AdminAuditService audit;
    public SupportInterventionService(JdbcTemplate jdbc, Clock clock, AdminAuditService audit) {
        this.jdbc = jdbc; this.clock = clock; this.audit = audit;
    }

    @Transactional
    public State state(UUID ticketId) { return state(ticket(ticketId, false)); }

    @Transactional
    public State apply(UUID ticketId, Request request) {
        Request normalized = normalize(request);
        String fingerprint = fingerprint(normalized, audit.currentUsername());
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, row) -> 0,
                "support-intervention-request:" + normalized.requestId());
        Ticket ticket = ticket(ticketId, true);
        var previous = jdbc.query("select * from saas_support_intervention_event where request_id=?", (rs, row) ->
                new Receipt(rs.getObject("ticket_id", UUID.class), rs.getLong("version") - 1,
                        rs.getString("expected_ticket_status"), rs.getString("action"), rs.getString("note"),
                        rs.getString("requested_team_viewer_id"), rs.getString("actor"), rs.getString("request_fingerprint")), normalized.requestId());
        if (!previous.isEmpty()) {
            Receipt saved = previous.getFirst();
            boolean same = saved.ticketId().equals(ticketId) && (saved.fingerprint() != null
                    ? saved.fingerprint().equals(fingerprint)
                    : saved.equals(new Receipt(ticketId, normalized.expectedVersion(), normalized.expectedTicketStatus(),
                        normalized.action(), normalized.note(), normalized.teamViewerId(), audit.currentUsername(), null))
                        && normalized.assignee() == null && normalized.visitAt() == null && normalized.resolutionSummary() == null
                        && normalized.verificationNotes() == null && normalized.confirmedBy() == null
                        && normalized.assigneeUserId() == null && normalized.nextReviewAt() == null);
            if (!same) throw conflict("requestId ya utilizado para otra intervencion");
            return state(ticket);
        }
        if (normalized.assignee() != null) throw badRequest("Seleccione un tecnico por su identificador");
        State current = state(ticket);
        if (current.version() != normalized.expectedVersion() || !current.ticketStatus().equals(normalized.expectedTicketStatus()))
            throw conflict("El seguimiento ha cambiado; actualice antes de continuar");
        String action = normalized.action();
        String next = "RESUME".equals(action)
                ? require(current.status(), WAITING, current.resumeStatus()) : transition(current.status(), action);
        boolean waiting = "WAIT_CUSTOMER".equals(action) || "WAIT_MATERIAL".equals(action);
        if (waiting && (normalized.nextReviewAt() == null || !normalized.nextReviewAt().isAfter(clock.instant())))
            throw badRequest("Indique una proxima revision en el futuro");
        String resumeStatus = waiting ? (WAITING.contains(current.status()) ? current.resumeStatus() : current.status())
                : "SAVE_DETAILS".equals(action) ? current.resumeStatus() : null;
        Instant nextReviewAt = waiting ? normalized.nextReviewAt() : "SAVE_DETAILS".equals(action) ? current.nextReviewAt() : null;
        boolean planning = "SAVE_DETAILS".equals(action);
        boolean reopen = "REOPEN".equals(action) || "VERIFICATION_FAILED".equals(action);
        UUID assigneeUserId = planning ? normalized.assigneeUserId() : current.assigneeUserId();
        String assignee = planning ? null : current.assignee();
        if (planning && assigneeUserId != null) assignee = technician(assigneeUserId).username();
        if (!planning && assignee == null && Set.of("START_SAAS", "START_REMOTE", "START_ONSITE").contains(action)) {
            var operator = assignees().stream().filter(user -> user.username().equalsIgnoreCase(audit.currentUsername())).findFirst();
            if (operator.isPresent()) { assigneeUserId = operator.get().id(); assignee = operator.get().username(); }
        }
        Instant visitAt = planning ? normalized.visitAt() : reopen ? null : current.visitAt();
        if ("START_ONSITE".equals(action) && (visitAt == null || assigneeUserId == null)) throw conflict("Registre el responsable y la fecha de visita antes de iniciarla");
        if ("START_ONSITE".equals(action)) technician(assigneeUserId);
        if (planning && visitAt != null && assigneeUserId == null) throw badRequest("Asigne un tecnico para programar una visita");
        if (planning && ("ONSITE_IN_PROGRESS".equals(current.status()) || "ONSITE_IN_PROGRESS".equals(current.resumeStatus()))
                && (visitAt == null || assigneeUserId == null)) throw badRequest("La visita en curso necesita responsable y fecha");
        String resolution = "REQUEST_VERIFICATION".equals(action) ? required(normalized.resolutionSummary(), 5, 2000, "Describa la solucion aplicada")
                : reopen ? null : current.resolutionSummary();
        String checks = "RESOLVE".equals(action) ? required(normalized.verificationNotes(), 5, 2000, "Registre las comprobaciones realizadas")
                : reopen ? null : current.verificationNotes();
        String confirmed = "RESOLVE".equals(action) ? required(normalized.confirmedBy(), 2, 120, "Indique quien confirma el funcionamiento")
                : reopen ? null : current.confirmedBy();
        if ("RESOLVE".equals(action)) required(resolution, 5, 2000, "Falta la solucion aplicada");
        String teamViewer = "START_REMOTE".equals(action) ? normalized.teamViewerId() : current.teamViewerId();
        long version = current.version() + 1;
        String ticketStatus = planning ? current.ticketStatus() : "RESOLVED".equals(next) ? "RESUELTO" : reopen ? "ABIERTO" : "EN_CURSO";
        jdbc.update("""
                insert into saas_support_intervention(ticket_id,status,version,team_viewer_id,assignee,visit_at,resolution_summary,verification_notes,confirmed_by,assignee_user_id,next_review_at,resume_status)
                values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict(ticket_id) do update set status=excluded.status,version=excluded.version,
                team_viewer_id=excluded.team_viewer_id,assignee=excluded.assignee,visit_at=excluded.visit_at,
                resolution_summary=excluded.resolution_summary,verification_notes=excluded.verification_notes,confirmed_by=excluded.confirmed_by,
                assignee_user_id=excluded.assignee_user_id,next_review_at=excluded.next_review_at,resume_status=excluded.resume_status
                """, ticketId, next, version, teamViewer, assignee, timestamp(visitAt), resolution, checks, confirmed, assigneeUserId, timestamp(nextReviewAt), resumeStatus);
        jdbc.update("""
                insert into saas_support_intervention_event(request_id,ticket_id,version,expected_ticket_status,action,status,note,
                    team_viewer_id,requested_team_viewer_id,actor,created_at,assignee,visit_at,resolution_summary,verification_notes,confirmed_by,request_fingerprint,assignee_user_id,next_review_at,resume_status)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, normalized.requestId(), ticketId, version, normalized.expectedTicketStatus(), action, next,
                normalized.note(), teamViewer, normalized.teamViewerId(), audit.currentUsername(), timestamp(clock.instant()),
                assignee, timestamp(visitAt), resolution, checks, confirmed, fingerprint, assigneeUserId, timestamp(nextReviewAt), resumeStatus);
        jdbc.update("update saas_support_ticket set status=?,updated_at=? where id=?", ticketStatus, timestamp(clock.instant()), ticketId);
        audit.log("SUPPORT_INTERVENTION", "SUPPORT_TICKET", ticketId.toString(),
                "requestId=" + normalized.requestId() + "; action=" + action + "; version=" + version);
        return state(new Ticket(ticket.id(), ticket.companyId(), ticketStatus));
    }

    private Ticket ticket(UUID id, boolean write) {
        return jdbc.query("""
                select t.id,t.company_id,t.status from saas_support_ticket t where t.id=?
                  and not exists(select 1 from saas_store_failure_manual m where m.ticket_id=t.id and m.company_id<>t.company_id)
                """ + (write ? " for update of t" : " for share of t"),
                (rs, row) -> new Ticket(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class), rs.getString("status")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no encontrado"));
    }
    private State state(Ticket ticket) {
        var rows = jdbc.query("select * from saas_support_intervention where ticket_id=?", (rs, row) ->
                new Current(rs.getString("status"), rs.getLong("version"), rs.getString("team_viewer_id"), rs.getString("assignee"),
                        instant(rs,"visit_at"), rs.getString("resolution_summary"), rs.getString("verification_notes"), rs.getString("confirmed_by"), rs.getObject("assignee_user_id",UUID.class),
                        instant(rs,"next_review_at"),rs.getString("resume_status")), ticket.id());
        Current current = rows.isEmpty() ? new Current("REMOTE_PENDING", 0, null, null, null, null, null, null, null, null, null) : rows.getFirst();
        // Keep historical tickets closed without inventing verification evidence.
        String status = "RESUELTO".equals(ticket.status()) ? "RESOLVED"
                : "RESOLVED".equals(current.status()) ? "REMOTE_PENDING" : current.status();
        List<Event> events = jdbc.query("select * from saas_support_intervention_event where ticket_id=? order by version", this::event, ticket.id());
        var links = jdbc.query("""
                select m.failure_key,f.status,f.code,s.name,f.received_at from saas_store_failure_manual m
                join saas_store_failure f on m.failure_key=f.source||':'||f.id::text and f.company_id=m.company_id
                left join saas_store s on s.id=f.store_id and s.company_id=f.company_id
                where m.ticket_id=? and m.company_id=?
                """, (rs, row) -> new FailureLink(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()), ticket.id(), ticket.companyId());
        return new State(ticket.id(), ticket.companyId(), status, current.version(), ticket.status(), current.teamViewerId(), events,
                current.assignee(), current.visitAt(), current.resolutionSummary(), current.verificationNotes(), current.confirmedBy(), links.stream().findFirst().orElse(null),
                current.assigneeUserId(),current.nextReviewAt(),current.resumeStatus(),assignees());
    }
    private Event event(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getObject("request_id", UUID.class), rs.getLong("version"), rs.getString("action"),
                rs.getString("status"), rs.getString("note"), rs.getString("team_viewer_id"), rs.getString("actor"),
                instant(rs,"created_at"),rs.getString("assignee"),instant(rs,"visit_at"),rs.getString("resolution_summary"),
                rs.getString("verification_notes"),rs.getString("confirmed_by"),rs.getObject("assignee_user_id",UUID.class),instant(rs,"next_review_at"),rs.getString("resume_status"));
    }
    private static Request normalize(Request request) {
        if (request == null || request.requestId() == null || request.expectedVersion() == null || request.expectedVersion() < 0)
            throw badRequest("requestId y version obligatorios");
        if (request.action() == null || !ACTIONS.contains(request.action()) || request.expectedTicketStatus() == null
                || !TICKET_STATUSES.contains(request.expectedTicketStatus())) throw badRequest("Accion o estado no soportado");
        String note = required(request.note(),5,2000,"La nota debe tener entre 5 y 2000 caracteres");
        String teamViewer = request.teamViewerId() == null || request.teamViewerId().isEmpty() ? null : request.teamViewerId();
        if (teamViewer != null && !teamViewer.matches("[0-9]{6,15}")) throw badRequest("TeamViewer ID debe contener entre 6 y 15 digitos");
        if (teamViewer != null && !"START_REMOTE".equals(request.action())) throw badRequest("TeamViewer ID solo se admite al iniciar asistencia remota");
        String assignee = optional(request.assignee(),120);
        Instant visit = request.visitAt() == null ? null : request.visitAt().truncatedTo(ChronoUnit.MICROS);
        String resolution = optional(request.resolutionSummary(),2000), checks = optional(request.verificationNotes(),2000), confirmed = optional(request.confirmedBy(),120);
        if (!"SAVE_DETAILS".equals(request.action()) && (request.assigneeUserId() != null || visit != null))
            throw badRequest("La planificacion se guarda con SAVE_DETAILS");
        Instant review = request.nextReviewAt() == null ? null : request.nextReviewAt().truncatedTo(ChronoUnit.MICROS);
        if (review != null && !Set.of("WAIT_CUSTOMER","WAIT_MATERIAL").contains(request.action()))
            throw badRequest("La fecha de revision se registra al dejar el caso en espera");
        if (resolution != null && !"REQUEST_VERIFICATION".equals(request.action())) throw badRequest("La solucion se registra al solicitar comprobacion");
        if ((checks != null || confirmed != null) && !"RESOLVE".equals(request.action())) throw badRequest("La comprobacion se registra al cerrar");
        return new Request(request.requestId(),request.expectedVersion(),request.expectedTicketStatus(),request.action(),note,teamViewer,assignee,visit,resolution,checks,confirmed,request.assigneeUserId(),review);
    }
    private static String optional(String raw, int max) {
        if (raw == null) return null;
        com.tpverp.saas.DatabaseText.requireValid(raw);
        String text = raw.trim();
        if (text.codePointCount(0,text.length()) > max) throw badRequest("Texto demasiado largo");
        return text.isEmpty() ? null : text;
    }
    private static String required(String raw, int min, int max, String message) {
        String value = optional(raw,max);
        if (value == null || value.codePointCount(0,value.length()) < min) throw badRequest(message);
        return value;
    }
    private static String transition(String current, String action) {
        return switch (action) {
            case "SAVE_DETAILS" -> { if (!ACTIVE.contains(current) && !WAITING.contains(current)) throw conflict("No se puede planificar una incidencia cerrada"); yield current; }
            case "START_SAAS" -> require(current, Set.of("REMOTE_PENDING"), "SAAS_IN_PROGRESS");
            case "WAIT_CUSTOMER", "WAIT_MATERIAL" -> {
                if (!ACTIVE.contains(current) && !WAITING.contains(current)) throw conflict("No se puede poner en espera una incidencia cerrada");
                yield "WAIT_CUSTOMER".equals(action) ? "WAITING_CUSTOMER" : "WAITING_MATERIAL";
            }
            case "START_REMOTE" -> require(current, Set.of("REMOTE_PENDING","SAAS_IN_PROGRESS"), "REMOTE_IN_PROGRESS");
            case "REQUIRE_ONSITE" -> require(current, Set.of("REMOTE_PENDING", "SAAS_IN_PROGRESS", "REMOTE_IN_PROGRESS","AWAITING_VERIFICATION"), "ONSITE_REQUIRED");
            case "START_ONSITE" -> require(current, Set.of("ONSITE_REQUIRED"), "ONSITE_IN_PROGRESS");
            case "REQUEST_VERIFICATION" -> require(current, Set.of("SAAS_IN_PROGRESS", "REMOTE_IN_PROGRESS", "ONSITE_IN_PROGRESS"), "AWAITING_VERIFICATION");
            case "VERIFICATION_FAILED" -> require(current, Set.of("AWAITING_VERIFICATION"), "REMOTE_PENDING");
            case "RESOLVE" -> require(current, Set.of("AWAITING_VERIFICATION"), "RESOLVED");
            case "REOPEN" -> require(current, Set.of("RESOLVED"), "REMOTE_PENDING");
            default -> throw badRequest("Accion no soportada");
        };
    }
    private static String fingerprint(Request request, String actor) {
        StringBuilder canonical = new StringBuilder();
        Object[] values = {actor,request.requestId(),request.expectedVersion(),request.expectedTicketStatus(),request.action(),request.note(),
                request.teamViewerId(),request.assignee(),request.visitAt(),request.resolutionSummary(),request.verificationNotes(),request.confirmedBy()};
        for (Object value : values) {
            if (value == null) canonical.append("-1:");
            else { String text = value.toString(); canonical.append(text.length()).append(':').append(text); }
        }
        // Keep the V73 canonical form unchanged when the extension is absent, so an in-flight retry remains valid.
        if (request.assigneeUserId() != null || request.nextReviewAt() != null) {
            canonical.append("|V74|");
            for (Object value : new Object[]{request.assigneeUserId(),request.nextReviewAt()}) {
                if (value == null) canonical.append("-1:");
                else { String text=value.toString(); canonical.append(text.length()).append(':').append(text); }
            }
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private List<Assignee> assignees() {
        return jdbc.query("""
                select u.id,u.username from saas_admin_user u where u.active
                and exists(select 1 from saas_admin_user_role ur join saas_admin_role_permission rp on rp.role_id=ur.role_id
                           where ur.user_id=u.id and rp.permission_code='MANAGE_SUPPORT_TICKETS')
                order by lower(u.username),u.id
                """, (rs,row)->new Assignee(rs.getObject("id",UUID.class),rs.getString("username")));
    }
    private Assignee technician(UUID id) {
        return assignees().stream().filter(user->user.id().equals(id)).findFirst()
                .orElseThrow(()->badRequest("El tecnico debe estar activo y tener permiso para gestionar soporte"));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { var value=rs.getTimestamp(column); return value==null?null:value.toInstant(); }
    private static String require(String current, Set<String> allowed, String next) {
        if (!allowed.contains(current)) throw conflict("Accion incompatible con el estado actual");
        return next;
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Ticket(UUID id, UUID companyId, String status) { }
    private record Current(String status, long version, String teamViewerId, String assignee, Instant visitAt, String resolutionSummary, String verificationNotes, String confirmedBy, UUID assigneeUserId, Instant nextReviewAt, String resumeStatus) { }
    private record Receipt(UUID ticketId, long expectedVersion, String expectedTicketStatus, String action, String note, String teamViewerId, String actor, String fingerprint) { }
}

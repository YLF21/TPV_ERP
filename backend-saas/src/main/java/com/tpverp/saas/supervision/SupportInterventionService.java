package com.tpverp.saas.supervision;

import static com.tpverp.saas.supervision.SupportInterventionModels.*;
import com.tpverp.saas.admin.AdminAuditService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
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
    private static final Set<String> ACTIONS = Set.of("START_REMOTE", "REQUIRE_ONSITE", "START_ONSITE", "RESOLVE", "REOPEN");
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
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs, row) -> 0,
                "support-intervention-request:" + normalized.requestId());
        Ticket ticket = ticket(ticketId, true);
        var previous = jdbc.query("select * from saas_support_intervention_event where request_id=?", (rs, row) ->
                new Receipt(rs.getObject("ticket_id", UUID.class), rs.getLong("version") - 1,
                        rs.getString("expected_ticket_status"), rs.getString("action"), rs.getString("note"),
                        rs.getString("requested_team_viewer_id"), rs.getString("actor")), normalized.requestId());
        if (!previous.isEmpty()) {
            Receipt saved = previous.getFirst();
            if (!saved.equals(new Receipt(ticketId, normalized.expectedVersion(), normalized.expectedTicketStatus(),
                    normalized.action(), normalized.note(), normalized.teamViewerId(), audit.currentUsername())))
                throw conflict("requestId ya utilizado para otra intervencion");
            return state(ticket);
        }
        State current = state(ticket);
        if (current.version() != normalized.expectedVersion() || !current.ticketStatus().equals(normalized.expectedTicketStatus()))
            throw conflict("El seguimiento ha cambiado; actualice antes de continuar");
        String next = transition(current.status(), normalized.action());
        String teamViewer = "START_REMOTE".equals(normalized.action())
                ? normalized.teamViewerId() : current.teamViewerId();
        long version = current.version() + 1;
        String ticketStatus = "RESOLVED".equals(next) ? "RESUELTO" : "REOPEN".equals(normalized.action()) ? "ABIERTO" : "EN_CURSO";
        jdbc.update("""
                insert into saas_support_intervention(ticket_id,status,version,team_viewer_id) values (?,?,?,?)
                on conflict(ticket_id) do update set status=excluded.status,version=excluded.version,team_viewer_id=excluded.team_viewer_id
                """, ticketId, next, version, teamViewer);
        jdbc.update("""
                insert into saas_support_intervention_event(request_id,ticket_id,version,expected_ticket_status,action,status,note,
                    team_viewer_id,requested_team_viewer_id,actor,created_at) values (?,?,?,?,?,?,?,?,?,?,?)
                """, normalized.requestId(), ticketId, version, normalized.expectedTicketStatus(), normalized.action(), next,
                normalized.note(), teamViewer, normalized.teamViewerId(), audit.currentUsername(), Timestamp.from(clock.instant()));
        jdbc.update("update saas_support_ticket set status=?,updated_at=? where id=?", ticketStatus, Timestamp.from(clock.instant()), ticketId);
        audit.log("SUPPORT_INTERVENTION", "SUPPORT_TICKET", ticketId.toString(),
                "requestId=" + normalized.requestId() + "; action=" + normalized.action() + "; version=" + version);
        return state(new Ticket(ticket.id(), ticket.companyId(), ticketStatus));
    }

    private Ticket ticket(UUID id, boolean write) {
        return jdbc.query("""
                select t.id,t.company_id,t.status from saas_support_ticket t where t.id=?
                  and exists(select 1 from saas_store_failure_manual m where m.ticket_id=t.id and m.company_id=t.company_id)
                """ + (write ? " for update of t" : " for share of t"),
                (rs, row) -> new Ticket(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class), rs.getString("status")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket de fallo no encontrado"));
    }
    private State state(Ticket ticket) {
        var rows = jdbc.query("select status,version,team_viewer_id from saas_support_intervention where ticket_id=?",
                (rs, row) -> new Current(rs.getString("status"), rs.getLong("version"), rs.getString("team_viewer_id")), ticket.id());
        Current current = rows.isEmpty() ? new Current("REMOTE_PENDING", 0, null) : rows.getFirst();
        String status = "RESUELTO".equals(ticket.status()) ? "RESOLVED"
                : "RESOLVED".equals(current.status()) ? "REMOTE_PENDING" : current.status();
        List<Event> events = jdbc.query("select * from saas_support_intervention_event where ticket_id=? order by version", this::event, ticket.id());
        return new State(ticket.id(), ticket.companyId(), status, current.version(), ticket.status(), current.teamViewerId(), events);
    }
    private Event event(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getObject("request_id", UUID.class), rs.getLong("version"), rs.getString("action"),
                rs.getString("status"), rs.getString("note"), rs.getString("team_viewer_id"), rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant());
    }
    private static Request normalize(Request request) {
        if (request == null || request.requestId() == null || request.expectedVersion() == null || request.expectedVersion() < 0)
            throw badRequest("requestId y version obligatorios");
        if (request.action() == null || !ACTIONS.contains(request.action()) || request.expectedTicketStatus() == null
                || !TICKET_STATUSES.contains(request.expectedTicketStatus())) throw badRequest("Accion o estado no soportado");
        String rawNote = request.note() == null ? "" : request.note();
        com.tpverp.saas.DatabaseText.requireValid(rawNote);
        String note = rawNote.trim();
        if (note.codePointCount(0, note.length()) < 5 || note.codePointCount(0, note.length()) > 2000) throw badRequest("La nota debe tener entre 5 y 2000 caracteres");
        String teamViewer = request.teamViewerId() == null || request.teamViewerId().isEmpty() ? null : request.teamViewerId();
        if (teamViewer != null && !teamViewer.matches("[0-9]{6,15}")) throw badRequest("TeamViewer ID debe contener entre 6 y 15 digitos");
        if (teamViewer != null && !"START_REMOTE".equals(request.action())) throw badRequest("TeamViewer ID solo se admite al iniciar asistencia remota");
        return new Request(request.requestId(), request.expectedVersion(), request.expectedTicketStatus(), request.action(), note, teamViewer);
    }
    private static String transition(String current, String action) {
        return switch (action) {
            case "START_REMOTE" -> require(current, Set.of("REMOTE_PENDING"), "REMOTE_IN_PROGRESS");
            case "REQUIRE_ONSITE" -> require(current, Set.of("REMOTE_PENDING", "REMOTE_IN_PROGRESS"), "ONSITE_REQUIRED");
            case "START_ONSITE" -> require(current, Set.of("ONSITE_REQUIRED"), "ONSITE_IN_PROGRESS");
            case "RESOLVE" -> require(current, Set.of("REMOTE_IN_PROGRESS", "ONSITE_IN_PROGRESS"), "RESOLVED");
            case "REOPEN" -> require(current, Set.of("RESOLVED"), "REMOTE_PENDING");
            default -> throw badRequest("Accion no soportada");
        };
    }
    private static String require(String current, Set<String> allowed, String next) {
        if (!allowed.contains(current)) throw conflict("Accion incompatible con el estado actual");
        return next;
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Ticket(UUID id, UUID companyId, String status) { }
    private record Current(String status, long version, String teamViewerId) { }
    private record Receipt(UUID ticketId, long expectedVersion, String expectedTicketStatus, String action, String note, String teamViewerId, String actor) { }
}
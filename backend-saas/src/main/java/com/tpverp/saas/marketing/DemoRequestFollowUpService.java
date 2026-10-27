package com.tpverp.saas.marketing;

import com.tpverp.saas.admin.AdminAuditService;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoRequestFollowUpService {
    static final int NOTE_LIMIT = 100;
    private final DemoRequestRepository requests;
    private final DemoRequestFollowUpRepository followUp;
    private final AdminAuditService audit;
    private final Clock clock;

    public DemoRequestFollowUpService(DemoRequestRepository requests, DemoRequestFollowUpRepository followUp,
            AdminAuditService audit, Clock clock) {
        this.requests = requests;
        this.followUp = followUp;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DemoRequestAssignee> assignees() {
        return followUp.assignees();
    }

    // Keep the summary version and its notes in the same database snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DemoRequestFollowUp detail(UUID id) {
        return detail(request(id));
    }

    @Transactional
    public DemoRequestFollowUp update(UUID id, UpdateDemoRequestFollowUp change) {
        DemoRequestView current = request(id);
        String username = text(change.assigneeUsername(), 80, "assigneeUsername");
        String nextAction = text(change.nextAction(), 500, "nextAction");
        String note = text(change.note(), 2000, "note");
        if (change.expectedVersion() == null || change.expectedVersion() < 0) {
            throw invalid("expectedVersion");
        }
        if (change.nextActionAt() != null && nextAction == null) throw invalid("nextAction");
        if (current.followUpVersion() != change.expectedVersion()) throw conflict();
        UUID assignee = username == null ? null : followUp.eligibleAssignee(username)
                .orElseThrow(() -> invalid("assigneeUsername"));
        if (!followUp.update(id, assignee, nextAction, change.nextActionAt(), change.expectedVersion())) {
            throw conflict();
        }
        if (note != null) {
            followUp.appendNote(id, new DemoRequestNote(UUID.randomUUID(), audit.currentUsername(), note,
                    clock.instant().truncatedTo(ChronoUnit.MICROS)));
        }
        // Never copy notes, next-action text, contact data or account names to the audit log.
        audit.log("UPDATE_DEMO_REQUEST_FOLLOW_UP", "MARKETING_DEMO_REQUEST", id.toString(),
                note == null ? "FOLLOW_UP_UPDATED" : "FOLLOW_UP_UPDATED_WITH_NOTE");
        return detail(request(id));
    }

    private DemoRequestView request(UUID id) {
        return requests.findById(id).orElseThrow(() ->
                new DemoRequestFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Solicitud no encontrada"));
    }

    private DemoRequestFollowUp detail(DemoRequestView request) {
        List<DemoRequestNote> notes = followUp.notes(request.id(), NOTE_LIMIT + 1);
        return new DemoRequestFollowUp(request, notes.subList(0, Math.min(notes.size(), NOTE_LIMIT)),
                notes.size() > NOTE_LIMIT);
    }

    private static DemoRequestFailure conflict() {
        return new DemoRequestFailure(HttpStatus.CONFLICT, "FOLLOW_UP_CONFLICT",
                "El seguimiento ha cambiado. Actualiza la solicitud antes de guardar");
    }

    private static DemoRequestFailure invalid(String field) {
        return new DemoRequestFailure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "El seguimiento contiene campos invalidos", Map.of(field, "INVALID"), null);
    }

    private static String text(String value, int limit, String field) {
        if (value == null) return null;
        if (value.length() > limit) throw invalid(field);
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

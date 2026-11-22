package com.tpverp.saas.access;

import static com.tpverp.saas.access.ClientAccessRequests.*;
import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.plan.PlanLimitService;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClientAccessReviewService {
    private final JdbcTemplate jdbc;
    private final ClientAccessRequestQuery query;
    private final TenantAccessService access;
    private final AdminAuditService audit;
    private final PlanLimitService limits;

    public ClientAccessReviewService(JdbcTemplate jdbc, ClientAccessRequestQuery query,
            TenantAccessService access, AdminAuditService audit, PlanLimitService limits) {
        this.jdbc = jdbc;
        this.query = query;
        this.access = access;
        this.audit = audit;
        this.limits = limits;
    }

    @Transactional
    public Detail approve(UUID id, Approval approval) {
        String username = lockPending(id, approval.version());
        Set<UUID> companies = new TreeSet<>(Comparator.comparing(UUID::toString));
        for (Grant grant : approval.grants()) {
            if (!companies.add(grant.companyId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empresa repetida en las asignaciones");
            }
        }
        // Legacy manual creation locks company capacity before the global username.
        // PostgreSQL orders uuid values by unsigned bytes, as in the V61 reactivation trigger.
        companies.forEach(limits::lockCompany);
        UUID accountId = UUID.randomUUID();
        try {
            // The V36 trigger owns cross-realm username uniqueness. No existing identity can be reused here.
            jdbc.update("""
                    insert into saas_tenant_user (id, username, password_hash, role_name, active, created_at)
                    select ?, username, password_hash, 'VIEWER', true, current_timestamp
                    from saas_client_access_request where id = ?
                    """, accountId, id);
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El nombre de usuario ya pertenece a una cuenta SaaS");
        }
        for (Grant grant : approval.grants()) {
            access.replace(username, grant.companyId(), new UpdateTenantAccessRequest("VIEWER", Set.of(), grant.storeIds()));
        }
        return finish(id, Status.APPROVED, accountId, approval.reason(), !companies.isEmpty());
    }

    @Transactional
    public Detail reject(UUID id, Rejection rejection) {
        lockPending(id, rejection.version());
        return finish(id, Status.REJECTED, null, rejection.reason(), false);
    }

    private String lockPending(UUID id, long version) {
        return jdbc.query("select username, status, version from saas_client_access_request where id = ? for update",
                rs -> {
                    if (!rs.next()) throw ClientAccessRequestQuery.notFound();
                    if (!"PENDING".equals(rs.getString("status")) || rs.getLong("version") != version) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud ya ha cambiado");
                    }
                    return rs.getString("username");
                }, id);
    }

    private Detail finish(UUID id, Status status, UUID accountId, String reason, boolean hasStores) {
        if (reason != null && reason.indexOf(0) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo no válido");
        }
        jdbc.update("""
                update saas_client_access_request set status = ?, tenant_user_id = ?, reviewed_at = current_timestamp,
                    reviewed_by = ?, review_reason = ?, version = version + 1, password_hash = null where id = ?
                """, status.name(), accountId, audit.currentUsername(), reason == null || reason.isBlank() ? null : reason.strip(), id);
        audit.log(status == Status.APPROVED ? "APPROVE_CLIENT_ACCESS_REQUEST" : "REJECT_CLIENT_ACCESS_REQUEST",
                "CLIENT_ACCESS_REQUEST", id.toString(), accountId == null ? null : "tenantUserId=" + accountId);
        jdbc.update("insert into saas_client_decision_email(request_id, has_stores) values (?, ?)", id, hasStores);
        return query.detail(id);
    }

    @Transactional
    public Detail retryEmail(UUID id) {
        var detail = query.detail(id);
        if (detail.delivery() == null || jdbc.update("""
                update saas_client_decision_email set status='PENDING', attempts=0, next_attempt_at=null, last_error=null
                where request_id=? and status='FAILED'
                """, id) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El aviso no está marcado como fallido");
        }
        audit.log("RETRY_CLIENT_DECISION_EMAIL", "CLIENT_ACCESS_REQUEST", id.toString());
        return query.detail(id);
    }
}

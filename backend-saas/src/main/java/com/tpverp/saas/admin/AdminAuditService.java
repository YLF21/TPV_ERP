package com.tpverp.saas.admin;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AdminAuditService {

    public static final String USERNAME_ATTRIBUTE = "tpv.admin.username";

    private final SaasAdminAuditLogRepository audit;
    private final Clock clock;

    public AdminAuditService(SaasAdminAuditLogRepository audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void log(String action, String targetType, String targetId) {
        log(action, targetType, targetId, null);
    }

    @Transactional
    public void log(String action, String targetType, String targetId, String details) {
        String actor = authenticatedUsername();
        save(actor == null ? "system" : actor, actor == null ? "OTHER" : "SAAS_ADMIN",
                action, targetType, targetId, details);
    }

    /** For authentication flows where the credentials or token have already been verified. */
    @Transactional
    public void logAccountAction(String verifiedUsername, String action) {
        save(verifiedUsername, "SAAS_ADMIN", action, "ADMIN_USER", verifiedUsername, null);
    }

    /** The OAuth callback revalidates the staff account that created the single-use authorization. */
    @Transactional
    public void logMailConnection(String verifiedUsername, String provider, UUID accountId) {
        save(verifiedUsername, "SAAS_ADMIN", "CONNECT_MAIL_OAUTH", "SMTP_SETTINGS", accountId.toString(), provider);
    }

    @Transactional
    public void logOperational(String action, String targetType, String targetId, String details) {
        save("system", "OTHER", action, targetType, targetId, details);
    }

    private void save(String username, String origin, String action, String targetType, String targetId, String details) {
        audit.save(new SaasAdminAuditLog(UUID.randomUUID(), username, origin, action, targetType, targetId, details,
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AdminAuditLogResponse> recent() {
        return audit.findTop100ByOriginOrderByCreatedAtDescIdDesc("SAAS_ADMIN").stream()
                .map(value -> new AdminAuditLogResponse(
                        value.getId(),
                        value.getUsername(),
                        value.getAction(),
                        value.getTargetType(),
                        value.getTargetId(),
                        value.getDetails(),
                        value.getCreatedAt()))
                .toList();
    }

    public String currentUsername() {
        String actor = authenticatedUsername();
        return actor == null ? "system" : actor;
    }

    private String authenticatedUsername() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            Object username = servletAttributes.getRequest().getAttribute(USERNAME_ATTRIBUTE);
            if (username != null) {
                return username.toString();
            }
        }
        return null;
    }
}

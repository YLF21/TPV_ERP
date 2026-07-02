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

    static final String USERNAME_ATTRIBUTE = "tpv.admin.username";

    private final SaasAdminAuditLogRepository audit;
    private final Clock clock;

    public AdminAuditService(SaasAdminAuditLogRepository audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void log(String action, String targetType, String targetId) {
        audit.save(new SaasAdminAuditLog(
                UUID.randomUUID(),
                currentUsername(),
                action,
                targetType,
                targetId,
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AdminAuditLogResponse> recent() {
        return audit.findTop100ByOrderByCreatedAtDesc().stream()
                .map(value -> new AdminAuditLogResponse(
                        value.getId(),
                        value.getUsername(),
                        value.getAction(),
                        value.getTargetType(),
                        value.getTargetId(),
                        value.getCreatedAt()))
                .toList();
    }

    private String currentUsername() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            Object username = servletAttributes.getRequest().getAttribute(USERNAME_ATTRIBUTE);
            if (username != null) {
                return username.toString();
            }
        }
        return "system";
    }
}

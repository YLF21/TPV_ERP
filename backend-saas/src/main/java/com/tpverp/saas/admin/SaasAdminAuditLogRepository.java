package com.tpverp.saas.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasAdminAuditLogRepository extends JpaRepository<SaasAdminAuditLog, UUID> {

    List<SaasAdminAuditLog> findTop100ByOrderByCreatedAtDesc();
}

package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasMemberPointsBootstrapAbsorbedOperationRepository extends JpaRepository<SaasMemberPointsBootstrapAbsorbedOperation, UUID> {
    Optional<SaasMemberPointsBootstrapAbsorbedOperation> findFirstByCompanyIdAndOperationId(UUID companyId, UUID operationId);
}

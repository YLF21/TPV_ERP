package com.tpverp.backend.control;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ControlRuleRepository extends JpaRepository<ControlRule, UUID> {
    List<ControlRule> findAllByStoreIdOrderByTypeAsc(UUID storeId);
    @Query("select rule from ControlRule rule where rule.storeId = :storeId and rule.type = :type "
            + "and rule.active = true and rule.retired = false")
    List<ControlRule> findAllByStoreIdAndTypeAndActiveTrue(UUID storeId, ControlAlertType type);
    Optional<ControlRule> findByIdAndStoreId(UUID id, UUID storeId);
    Optional<ControlRule> findByStoreIdAndType(UUID storeId, ControlAlertType type);
}

package com.tpverp.backend.control;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlRuleRepository extends JpaRepository<ControlRule, UUID> {
    List<ControlRule> findAllByStoreIdOrderByTypeAsc(UUID storeId);
    List<ControlRule> findAllByStoreIdAndTypeAndActiveTrue(UUID storeId, ControlAlertType type);
    Optional<ControlRule> findByIdAndStoreId(UUID id, UUID storeId);
    boolean existsByStoreIdAndType(UUID storeId, ControlAlertType type);
}

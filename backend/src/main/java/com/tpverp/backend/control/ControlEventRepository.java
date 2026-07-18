package com.tpverp.backend.control;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlEventRepository extends JpaRepository<ControlEvent, UUID> {
    boolean existsByRuleIdAndSourceTypeAndSourceId(UUID ruleId, String sourceType, UUID sourceId);
}

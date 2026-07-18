package com.tpverp.backend.control;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ControlRuleVersionRepository extends JpaRepository<ControlRuleVersion, UUID> {
    List<ControlRuleVersion> findAllByRuleIdOrderByRuleVersionDesc(UUID ruleId);
}

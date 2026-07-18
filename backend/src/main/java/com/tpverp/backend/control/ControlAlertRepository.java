package com.tpverp.backend.control;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

public interface ControlAlertRepository
        extends JpaRepository<ControlAlert, UUID>, JpaSpecificationExecutor<ControlAlert> {

    @EntityGraph(attributePaths = "event")
    Optional<ControlAlert> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("""
            select alert.status as status, count(alert) as total
            from ControlAlert alert
            where alert.storeId = :storeId
            group by alert.status
            """)
    List<StatusCount> countByStoreIdGroupedByStatus(@Param("storeId") UUID storeId);

    @EntityGraph(attributePaths = "event")
    List<ControlAlert> findAllByStoreId(UUID storeId, Pageable pageable);

    @Query("""
            select event.ruleId as ruleId, alert.status as status, count(alert) as total
            from ControlAlert alert
            join alert.event event
            where alert.storeId = :storeId
              and event.occurredAt >= :from
              and event.occurredAt < :to
            group by event.ruleId, alert.status
            """)
    List<RuleStatusCount> countByRuleAndStatus(
            @Param("storeId") UUID storeId,
            @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to);

    interface StatusCount {
        ControlAlertStatus getStatus();

        long getTotal();
    }

    interface RuleStatusCount {
        UUID getRuleId();

        ControlAlertStatus getStatus();

        long getTotal();
    }
}

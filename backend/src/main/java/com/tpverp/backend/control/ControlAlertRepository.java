package com.tpverp.backend.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

public interface ControlAlertRepository
        extends JpaRepository<ControlAlert, UUID>, JpaSpecificationExecutor<ControlAlert> {

    @Override
    @EntityGraph(attributePaths = "event")
    Page<ControlAlert> findAll(Specification<ControlAlert> specification, Pageable pageable);

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
    @Query("""
            select alert from ControlAlert alert
            join alert.event event
            where alert.storeId = :storeId and event.storeId = :storeId
            """)
    List<ControlAlert> findAllByStoreId(@Param("storeId") UUID storeId, Pageable pageable);

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

    @Query("""
            select event.ruleId as ruleId, alert.status as status, count(alert) as total
            from ControlAlert alert
            join alert.event event
            where alert.storeId = :storeId
              and event.occurredAt >= :from and event.occurredAt < :to
              and (:status is null or alert.status = :status)
              and (:priority is null or alert.priority = :priority)
              and (:assigneeId is null or alert.assigneeId = :assigneeId)
              and (:overdue = false or (alert.status in ('NEW', 'REVIEWED')
                   and alert.dueAt is not null and alert.dueAt < :now))
              and (:search is null or lower(event.ruleName) like :search
                   or lower(event.userName) like :search
                   or lower(event.documentNumber) like :search)
            group by event.ruleId, alert.status
            """)
    List<RuleStatusCount> countByRuleAndStatusFiltered(
            @Param("storeId") UUID storeId, @Param("from") Instant from, @Param("to") Instant to,
            @Param("status") ControlAlertStatus status, @Param("priority") ControlAlertPriority priority,
            @Param("assigneeId") UUID assigneeId, @Param("overdue") boolean overdue,
            @Param("now") Instant now, @Param("search") String search);

    @Query("""
            select alert.status as status, count(alert) as total
            from ControlAlert alert
            join alert.event event
            where alert.storeId = :storeId
              and event.occurredAt >= :from
              and event.occurredAt < :to
            group by alert.status
            order by count(alert) desc
            """)
    List<StatusCount> countByStatusInRange(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select event.type as type, count(alert) as total
            from ControlAlert alert
            join alert.event event
            where alert.storeId = :storeId
              and event.occurredAt >= :from
              and event.occurredAt < :to
            group by event.type
            order by count(alert) desc
            """)
    List<TypeCount> countByTypeInRange(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select event.userId as userId, max(event.userName) as userName, count(alert) as total
            from ControlAlert alert
            join alert.event event
            where alert.storeId = :storeId
              and event.occurredAt >= :from
              and event.occurredAt < :to
            group by event.userId
            order by count(alert) desc
            """)
    List<UserCount> countByUserInRange(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select event.terminalId as terminalId, count(alert) as total
            from ControlAlert alert
            join alert.event event
            where alert.storeId = :storeId
              and event.occurredAt >= :from
              and event.occurredAt < :to
            group by event.terminalId
            order by count(alert) desc
            """)
    List<TerminalCount> countByTerminalInRange(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    long countByStoreIdAndStatusAndCreatedAtLessThanEqual(
            UUID storeId, ControlAlertStatus status, Instant threshold);

    @EntityGraph(attributePaths = "event")
    List<ControlAlert> findAllByStoreIdAndStatusAndCreatedAtLessThanEqual(
            UUID storeId, ControlAlertStatus status, Instant threshold, Pageable pageable);

    interface StatusCount {
        ControlAlertStatus getStatus();

        long getTotal();
    }

    interface RuleStatusCount {
        UUID getRuleId();

        ControlAlertStatus getStatus();

        long getTotal();
    }

    interface TypeCount {
        ControlAlertType getType();

        long getTotal();
    }

    interface UserCount {
        UUID getUserId();

        String getUserName();

        long getTotal();
    }

    interface TerminalCount {
        UUID getTerminalId();

        long getTotal();
    }
}

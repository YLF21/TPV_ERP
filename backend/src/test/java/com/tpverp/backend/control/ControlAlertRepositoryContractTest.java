package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

class ControlAlertRepositoryContractTest {

    @Test
    void statusAggregationIsScopedByStore() throws Exception {
        var method = ControlAlertRepository.class.getMethod(
                "countByStoreIdGroupedByStatus", UUID.class);

        var query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains(
                "where alert.storeId = :storeId",
                "group by alert.status");
    }

    @Test
    void recentAlertsFetchTheirEventForSummarySerialization() throws Exception {
        var method = ControlAlertRepository.class.getMethod(
                "findAllByStoreId", UUID.class, Pageable.class);

        var graph = method.getAnnotation(EntityGraph.class);

        assertThat(graph).isNotNull();
        assertThat(graph.attributePaths()).containsExactly("event");
    }

    @Test
    void ruleAggregationUsesEventDateAndStoreScope() throws Exception {
        var method = ControlAlertRepository.class.getMethod(
                "countByRuleAndStatus", UUID.class, java.time.Instant.class, java.time.Instant.class);

        var query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains(
                "alert.storeId = :storeId",
                "event.occurredAt >= :from",
                "event.occurredAt < :to",
                "group by event.ruleId, alert.status");
    }
}

package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class ControlAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void dashboardSummaryUsesCurrentStoreCountsAndFiveMostRecentAlerts() {
        var alerts = mock(ControlAlertRepository.class);
        var history = mock(ControlAlertHistoryRepository.class);
        var documents = mock(CommercialDocumentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);

        var newCount = count(ControlAlertStatus.NEW, 7);
        var reviewedCount = count(ControlAlertStatus.REVIEWED, 3);
        var closedCount = count(ControlAlertStatus.CLOSED, 11);
        when(alerts.countByStoreIdGroupedByStatus(storeId))
                .thenReturn(List.of(newCount, reviewedCount, closedCount));

        var userId = UUID.randomUUID();
        var rule = new ControlRule(
                storeId,
                ControlAlertType.TICKET_CANCELLED,
                true,
                Map.of(),
                userId,
                NOW);
        var event = new ControlEvent(
                storeId,
                rule,
                "DOCUMENT",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "T-100",
                UUID.randomUUID(),
                userId,
                "CAJERO",
                NOW,
                Map.of("reason", "Prueba"));
        var alert = new ControlAlert(event);
        when(alerts.findAllByStoreId(eq(storeId), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(alert));

        var service = new ControlAlertService(
                alerts,
                mock(ControlRuleRepository.class),
                history,
                documents,
                organization,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.dashboardSummary();

        assertThat(result.newCount()).isEqualTo(7);
        assertThat(result.reviewedCount()).isEqualTo(3);
        assertThat(result.recentAlerts()).singleElement().satisfies(recent -> {
            assertThat(recent.id()).isEqualTo(alert.getId());
            assertThat(recent.type()).isEqualTo(ControlAlertType.TICKET_CANCELLED);
            assertThat(recent.documentNumber()).isEqualTo("T-100");
        });
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(alerts).findAllByStoreId(eq(storeId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt"))
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void groupsConfiguredRulesByOccurrenceRangeAndExposesCurrentParameter() {
        var alerts = mock(ControlAlertRepository.class);
        var rules = mock(ControlRuleRepository.class);
        var history = mock(ControlAlertHistoryRepository.class);
        var documents = mock(CommercialDocumentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        var rule = new ControlRule(
                storeId, ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT, true,
                Map.of("thresholdPercent", 10), UUID.randomUUID(), NOW);
        when(rules.findAllByStoreIdOrderByTypeAsc(storeId)).thenReturn(List.of(rule));
        var count = mock(ControlAlertRepository.RuleStatusCount.class);
        when(count.getRuleId()).thenReturn(rule.getId());
        when(count.getStatus()).thenReturn(ControlAlertStatus.NEW);
        when(count.getTotal()).thenReturn(4L);
        var from = NOW.minusSeconds(3600);
        var to = NOW.plusSeconds(1);
        when(alerts.countByRuleAndStatus(storeId, from, to)).thenReturn(List.of(count));
        var service = new ControlAlertService(
                alerts, rules, history, documents, organization, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.countsByRule(from, to);

        assertThat(result).singleElement().satisfies(group -> {
            assertThat(group.ruleId()).isEqualTo(rule.getId());
            assertThat(group.total()).isEqualTo(4);
            assertThat(group.newCount()).isEqualTo(4);
            assertThat(group.parameterKind()).isEqualTo(ControlRuleParameterKind.PERCENTAGE);
            assertThat(new java.math.BigDecimal(group.configuration().get("thresholdPercent").toString()))
                    .isEqualByComparingTo("10");
            assertThat(group.supported()).isTrue();
        });
        verify(alerts).countByRuleAndStatus(storeId, from, to);
    }

    @Test
    void requiresBothOccurrenceBoundsWhenGroupingAlerts() {
        var service = new ControlAlertService(
                mock(ControlAlertRepository.class), mock(ControlRuleRepository.class),
                mock(ControlAlertHistoryRepository.class), mock(CommercialDocumentRepository.class),
                mock(CurrentOrganization.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.countsByRule(null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from y to");
        assertThatThrownBy(() -> service.countsByRule(NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from y to");
    }

    private static ControlAlertRepository.StatusCount count(
            ControlAlertStatus status, long total) {
        var count = mock(ControlAlertRepository.StatusCount.class);
        when(count.getStatus()).thenReturn(status);
        when(count.getTotal()).thenReturn(total);
        return count;
    }
}

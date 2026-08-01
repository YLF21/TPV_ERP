package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class ControlAlertAnalyticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @Test
    void aggregatesCurrentStoreAndIncludesOldNewAlertsWithoutMutatingThem() {
        var fixture = fixture();
        var from = NOW.minusSeconds(86_400);
        var to = NOW;

        var status = mock(ControlAlertRepository.StatusCount.class);
        when(status.getStatus()).thenReturn(ControlAlertStatus.NEW);
        when(status.getTotal()).thenReturn(4L);
        when(fixture.repository.countByStatusInRange(fixture.storeId, from, to))
                .thenReturn(List.of(status));

        var type = mock(ControlAlertRepository.TypeCount.class);
        when(type.getType()).thenReturn(ControlAlertType.TICKET_CANCELLED);
        when(type.getTotal()).thenReturn(4L);
        when(fixture.repository.countByTypeInRange(fixture.storeId, from, to))
                .thenReturn(List.of(type));

        var userId = UUID.randomUUID();
        var user = mock(ControlAlertRepository.UserCount.class);
        when(user.getUserId()).thenReturn(userId);
        when(user.getUserName()).thenReturn("CAJERO");
        when(user.getTotal()).thenReturn(4L);
        when(fixture.repository.countByUserInRange(fixture.storeId, from, to))
                .thenReturn(List.of(user));

        var terminalId = UUID.randomUUID();
        var terminal = mock(ControlAlertRepository.TerminalCount.class);
        when(terminal.getTerminalId()).thenReturn(terminalId);
        when(terminal.getTotal()).thenReturn(4L);
        when(fixture.repository.countByTerminalInRange(fixture.storeId, from, to))
                .thenReturn(List.of(terminal));

        var threshold = NOW.minusSeconds(24 * 3600);
        when(fixture.repository.countByStoreIdAndStatusAndCreatedAtLessThanEqual(
                fixture.storeId, ControlAlertStatus.NEW, threshold)).thenReturn(2L);
        var overdue = alert(fixture.storeId, NOW.minusSeconds(30 * 3600));
        when(fixture.repository.findAllByStoreIdAndStatusAndCreatedAtLessThanEqual(
                eq(fixture.storeId), eq(ControlAlertStatus.NEW), eq(threshold), any(Pageable.class)))
                .thenReturn(List.of(overdue));

        var result = fixture.service.analytics(from, to, 24);

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.overdueCount()).isEqualTo(2);
        assertThat(result.byStatus()).containsExactly(
                new ControlAlertAnalyticsService.KeyMetric("NEW", 4));
        assertThat(result.byType()).containsExactly(
                new ControlAlertAnalyticsService.KeyMetric("TICKET_CANCELLED", 4));
        assertThat(result.byUser()).containsExactly(
                new ControlAlertAnalyticsService.LabeledMetric(userId.toString(), "CAJERO", 4));
        assertThat(result.byTerminal()).containsExactly(
                new ControlAlertAnalyticsService.LabeledMetric(
                        terminalId.toString(), terminalId.toString(), 4));
        assertThat(result.overdueItems()).singleElement()
                .extracting(ControlAlertAnalyticsService.EscalationCandidate::id)
                .isEqualTo(overdue.getId());
    }

    @Test
    void escalationCandidatesAreScopedAndOrderedOldestFirst() {
        var fixture = fixture();
        var threshold = NOW.minusSeconds(48 * 3600);
        when(fixture.repository.countByStoreIdAndStatusAndCreatedAtLessThanEqual(
                fixture.storeId, ControlAlertStatus.NEW, threshold)).thenReturn(3L);
        when(fixture.repository.findAllByStoreIdAndStatusAndCreatedAtLessThanEqual(
                eq(fixture.storeId), eq(ControlAlertStatus.NEW), eq(threshold), any(Pageable.class)))
                .thenReturn(List.of());

        var result = fixture.service.escalationCandidates(48, 15);

        assertThat(result.count()).isEqualTo(3);
        assertThat(result.threshold()).isEqualTo(threshold);
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.repository).findAllByStoreIdAndStatusAndCreatedAtLessThanEqual(
                eq(fixture.storeId), eq(ControlAlertStatus.NEW), eq(threshold), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(15);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt"))
                .extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void validatesRangeAndEscalationParameters() {
        var service = fixture().service;

        assertThatThrownBy(() -> service.analytics(null, NOW, 24))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from y to");
        assertThatThrownBy(() -> service.analytics(NOW, NOW, 24))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("anterior");
        assertThatThrownBy(() -> service.analytics(NOW.minusSeconds(1), NOW, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 y 720");
        assertThatThrownBy(() -> service.escalationCandidates(721, 25))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 y 720");
        assertThatThrownBy(() -> service.escalationCandidates(24, 101))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    }

    private static Fixture fixture() {
        var repository = mock(ControlAlertRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        return new Fixture(
                repository,
                storeId,
                new ControlAlertAnalyticsService(
                        repository, organization, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static ControlAlert alert(UUID storeId, Instant occurredAt) {
        var userId = UUID.randomUUID();
        var rule = new ControlRule(
                storeId, ControlAlertType.TICKET_CANCELLED, true, Map.of(), userId, occurredAt);
        var event = new ControlEvent(
                storeId, rule, "DOCUMENT", UUID.randomUUID(), UUID.randomUUID(), "T-1",
                UUID.randomUUID(), userId, "CAJERO", occurredAt, Map.of());
        return new ControlAlert(event);
    }

    private record Fixture(
            ControlAlertRepository repository,
            UUID storeId,
            ControlAlertAnalyticsService service) {
    }
}

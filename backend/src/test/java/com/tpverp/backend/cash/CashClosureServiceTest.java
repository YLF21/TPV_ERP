package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;

class CashClosureServiceTest {

    @Test
    void defaultsToTheCurrentStoreDayAndBuildsAStableNextCursor() {
        var fixture = fixture(Instant.parse("2026-07-31T23:30:00Z"));
        var first = row("TPV 1", "tpv 1", Instant.parse("2026-08-01T10:00:00Z"));
        var second = row("TPV 1", "tpv 1", Instant.parse("2026-08-01T09:00:00Z"));
        when(fixture.repository().findClosures(
                eq(fixture.store().getId()), any(), any(), eq(null), eq(null),
                eq(false), eq(null), eq(2)))
                .thenReturn(List.of(first, second));

        var page = fixture.service().list(
                null, null, null, null, false, 1, null, authentication());

        assertThat(page.items()).extracting(CashClosureView::id).containsExactly(first.id());
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        var from = ArgumentCaptor.forClass(Instant.class);
        var to = ArgumentCaptor.forClass(Instant.class);
        verify(fixture.repository()).findClosures(
                eq(fixture.store().getId()), from.capture(), to.capture(), eq(null), eq(null),
                eq(false), eq(null), eq(2));
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-07-31T23:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-08-01T23:00:00Z"));
        verify(fixture.permissions()).requireReportPermission(any());
    }

    @Test
    void decodesTheCursorAndKeepsAllFiltersOnTheNextPage() {
        var fixture = fixture(Instant.parse("2026-07-31T10:00:00Z"));
        var terminalId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var cursorId = UUID.randomUUID();
        var cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("terminal a".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".1785492000000." + cursorId;
        when(fixture.repository().findClosures(
                eq(fixture.store().getId()), any(), any(), eq(terminalId), eq(userId),
                eq(true), any(), eq(51)))
                .thenReturn(List.of());

        fixture.service().list(
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"),
                terminalId, userId, true, 50, cursor, authentication());

        var decoded = ArgumentCaptor.forClass(CashClosureQueryRepository.CashClosureCursor.class);
        verify(fixture.repository()).findClosures(
                eq(fixture.store().getId()), any(), any(), eq(terminalId), eq(userId),
                eq(true), decoded.capture(), eq(51));
        assertThat(decoded.getValue().terminalSortKey()).isEqualTo("terminal a");
        assertThat(decoded.getValue().id()).isEqualTo(cursorId);
    }

    @Test
    void returnsHistoricalFilterOptionsAndBusinessDate() {
        var fixture = fixture(Instant.parse("2026-07-31T23:30:00Z"));
        var terminal = new CashClosureFilterOptionView(UUID.randomUUID(), "TPV 1", "");
        var user = new CashClosureFilterOptionView(UUID.randomUUID(), "CAJERO", "cajero");
        when(fixture.repository().findTerminalOptions(fixture.store().getId())).thenReturn(List.of(terminal));
        when(fixture.repository().findUserOptions(fixture.store().getId())).thenReturn(List.of(user));

        var options = fixture.service().filterOptions(authentication());

        assertThat(options.businessDate()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(options.timezone()).isEqualTo("Atlantic/Canary");
        assertThat(options.terminals()).containsExactly(terminal);
        assertThat(options.users()).containsExactly(user);
    }

    @Test
    void rejectsInvalidRangesLimitsAndCursors() {
        var fixture = fixture(Instant.parse("2026-07-31T10:00:00Z"));

        assertThatThrownBy(() -> fixture.service().list(
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-07-31"),
                null, null, false, 50, null, authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rango");
        assertThatThrownBy(() -> fixture.service().list(
                null, null, null, null, false, 101, null, authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limite");
        assertThatThrownBy(() -> fixture.service().list(
                null, null, null, null, false, 50, "invalid", authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cursor");
    }

    private static CashClosureQueryRepository.CashClosureRow row(
            String terminalName,
            String terminalSortKey,
            Instant closedAt) {
        return new CashClosureQueryRepository.CashClosureRow(
                UUID.randomUUID(), UUID.randomUUID(), terminalName, terminalSortKey,
                UUID.randomUUID(), "CAJERO", "cajero", closedAt,
                new BigDecimal("120.00"), new BigDecimal("20.00"),
                new BigDecimal("-1.00"), false);
    }

    private static TestingAuthenticationToken authentication() {
        return new TestingAuthenticationToken("accounting", "token");
    }

    private static Fixture fixture(Instant now) {
        var repository = mock(CashClosureQueryRepository.class);
        var organization = mock(CurrentOrganization.class);
        var permissions = mock(CashPermissionService.class);
        var store = store();
        when(organization.currentStore()).thenReturn(store);
        var service = new CashClosureService(
                repository, organization, permissions, Clock.fixed(now, ZoneOffset.UTC));
        return new Fixture(service, repository, organization, permissions, store);
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(
            CashClosureService service,
            CashClosureQueryRepository repository,
            CurrentOrganization organization,
            CashPermissionService permissions,
            Store store) {
    }
}

package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.TerminalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CashActivityServiceTest {
    private final CashActivityRepository repository = mock(CashActivityRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final TerminalRepository terminals = mock(TerminalRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final UUID storeId = UUID.randomUUID();
    private final CashActivityService service = new CashActivityService(repository, organization, terminals, users,
            new CashPermissionService(users, null, organization),
            Clock.fixed(Instant.parse("2026-07-31T23:30:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        var store = mock(Store.class);
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(repository.find(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void defaultsToStoreBusinessDayAndChronologicalOrderAndEmptyMetadataUsesToday() {
        list(null, null, null, null, null, null, 50, null, null, null, "CASH_READ");
        verify(repository).find(storeId, Instant.parse("2026-07-31T23:00:00Z"), Instant.parse("2026-08-01T23:00:00Z"),
                null, null, null, null, "date", "asc", null, 51);
        var options = service.filterOptions(auth("CASH_READ"));
        assertThat(options.businessDate()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(options.earliestDate()).isEqualTo(options.businessDate());
    }

    @Test
    void usesLocalCalendarBoundariesAcrossBothDaylightSavingTransitionsAndAcceptsLongRanges() {
        list("2026-03-29", "2026-03-29", null, null, null, null, 50, null, null, null, "CASH_READ");
        verify(repository).find(storeId, Instant.parse("2026-03-29T00:00:00Z"), Instant.parse("2026-03-29T23:00:00Z"),
                null, null, null, null, "date", "asc", null, 51);
        list("2026-10-25", "2026-10-25", null, null, null, null, 50, null, null, null, "CASH_READ");
        verify(repository).find(storeId, Instant.parse("2026-10-24T23:00:00Z"), Instant.parse("2026-10-26T00:00:00Z"),
                null, null, null, null, "date", "asc", null, 51);
        list("2020-01-01", "2026-10-25", null, null, null, null, 50, null, null, null, "CASH_READ");
    }

    @Test
    void financialPermissionsRedactBothAmountsAndPreventAmountOrderingOrCursorLeaks() {
        var row = row("2026-08-01T12:00:00.123456Z");
        when(repository.find(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row, row));
        var reader = list(null, null, null, null, null, null, 1, null, null, null, "CASH_READ");
        assertThat(reader.items()).singleElement().satisfies(item -> {
            assertThat(item.amount()).isNull();
            assertThat(item.balance()).isNull();
            assertThat(item.reference()).isEqualTo("260801001");
            assertThat(item.terminalName()).isEqualTo("TPV 2");
        });
        for (var sort : List.of("quantity", "balance")) {
            assertThatThrownBy(() -> list(null, null, null, null, null, null, 1, null, sort, "asc", "CASH_READ"))
                    .isInstanceOf(AccessDeniedException.class);
        }
        for (var permission : List.of("ROLE_ADMIN", "GESTION_CUENTAS")) {
            assertThat(list(null, null, null, null, null, null, 1, null, null, null, permission).items())
                    .containsExactly(row.item());
        }
        assertThatThrownBy(() -> service.filterOptions(auth("UNRELATED"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> list(null, null, null, null, null, null, 1, null, null, null, "UNRELATED"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void continuationPreservesMicrosecondsAndRejectsChangedFiltersOrSort() {
        var row = row("2026-08-01T12:00:00.123456Z");
        when(repository.find(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row, row));
        var first = list(null, null, null, null, null, null, 1, null, null, null, "GESTION_CUENTAS");
        assertThat(first.hasMore()).isTrue();
        clearInvocations(repository);
        list(null, null, null, null, null, null, 1, first.nextCursor(), null, null, "GESTION_CUENTAS");
        var cursor = ArgumentCaptor.forClass(CashActivityRepository.Cursor.class);
        verify(repository).find(any(), any(), any(), any(), any(), any(), any(), any(), any(), cursor.capture(), eq(2));
        assertThat(cursor.getValue().occurredAt()).isEqualTo(row.item().occurredAt());
        assertThat(cursor.getValue().value()).isEqualTo(row.item().occurredAt().toString());
        assertThatThrownBy(() -> list(null, null, null, null, "ENTRADA", null, 1, first.nextCursor(), null, null, "GESTION_CUENTAS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list(null, null, null, null, null, null, 1, first.nextCursor(), "date", "desc", "GESTION_CUENTAS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list("2026-08-02", null, null, null, null, null, 1, first.nextCursor(), null, null, "GESTION_CUENTAS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsForeignFiltersAndMalformedInputBeforeQueryingCashEvents() {
        assertThatThrownBy(() -> list(null, null, UUID.randomUUID(), null, null, null, 50, null, null, null, "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Terminal");
        assertThatThrownBy(() -> list(null, null, null, UUID.randomUUID(), null, null, 50, null, null, null, "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Usuario");
        assertThatThrownBy(() -> list("2026-08-02", "2026-08-01", null, null, null, null, 50, null, null, null, "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class);
        for (var limit : List.of(0, 101)) {
            assertThatThrownBy(() -> list(null, null, null, null, null, null, limit, null, null, null, "CASH_READ"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> list(null, null, null, null, "COBRO_EFECTIVO", null, 50, null, null, null, "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list(null, null, null, null, null, "UNKNOWN", 50, null, null, null, "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list(null, null, null, null, null, null, 50, "invalid", null, null, "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list(null, null, null, null, null, null, 50, null, "date;drop table", null, "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list(null, null, null, null, null, null, 50, null, null, "asc;drop table", "CASH_READ"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).find(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void historicalActorCanBeFilteredAfterStoreAssignmentChanges() {
        var userId = UUID.randomUUID();
        when(repository.userHasStoreActivity(storeId, userId)).thenReturn(true);
        list(null, null, null, userId, null, null, 50, null, null, null, "CASH_READ");
        verify(repository).find(eq(storeId), any(), any(), isNull(), eq(userId), isNull(), isNull(),
                eq("date"), eq("asc"), isNull(), eq(51));
    }

    private com.tpverp.backend.shared.api.PagedResult<CashActivityView> list(String from, String to, UUID terminal,
            UUID user, String action, String state, int limit, String cursor, String sort, String direction, String permission) {
        return service.list(from == null ? null : LocalDate.parse(from), to == null ? null : LocalDate.parse(to),
                terminal, user, action, state, limit, cursor, sort, direction, auth(permission));
    }

    private static Authentication auth(String authority) {
        return new UsernamePasswordAuthenticationToken("reader", "token", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static CashActivityRepository.Row row(String at) {
        return new CashActivityRepository.Row(new CashActivityView(UUID.randomUUID().toString(), Instant.parse(at),
                UUID.randomUUID(), "reader", "Reader", "ENTRADA", "Entrada", BigDecimal.TEN, new BigDecimal("25.00"),
                "260801001", UUID.randomUUID(), CashSessionStatus.ABIERTA, "source", UUID.randomUUID(), null, "TPV 2"), at, 1);
    }
}

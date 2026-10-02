package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CashAlertServiceTest {
    private final CashAlertRepository repository = mock(CashAlertRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final Store store = mock(Store.class);
    private final UUID storeId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC);
    private final CashAlertService service = new CashAlertService(repository, organization,
            new CashPermissionService(null, null, organization), clock);
    private final UsernamePasswordAuthenticationToken accountant = auth("GESTION_CUENTAS");

    CashAlertServiceTest() {
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
    }

    @Test
    void paginationPreservesTimestampAndSourceWhileDatesUseStoreTimezone() {
        var row = alert("CLOSING", Instant.parse("2026-07-31T09:00:00.123456Z"));
        when(repository.find(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row, row));
        when(repository.pendingCount(storeId)).thenReturn(8L);
        var page = service.list(LocalDate.parse("2026-07-31"), LocalDate.parse("2026-07-31"),
                null, null, "PENDING", "CLOSING", 1, null, accountant);
        assertThat(page.items()).containsExactly(row);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.pendingCount()).isEqualTo(8);
        service.list(null, null, null, null, null, null, 1, page.nextCursor(), accountant);
        var cursor = ArgumentCaptor.forClass(CashAlertRepository.Cursor.class);
        verify(repository).find(eq(storeId), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), cursor.capture(), eq(2));
        assertThat(cursor.getValue()).isEqualTo(new CashAlertRepository.Cursor(row.occurredAt(), row.id(), "CLOSING"));
        verify(repository).find(eq(storeId), eq(Instant.parse("2026-07-30T23:00:00Z")),
                eq(Instant.parse("2026-07-31T23:00:00Z")), isNull(), isNull(), eq("PENDING"), eq("CLOSING"), isNull(), eq(2));
    }

    @Test
    void cashReaderGetsBothSourceMetadataWithoutAmountsAndCannotReview() {
        var rows = List.of(alert("OPENING", clock.instant()), alert("CLOSING", clock.instant()));
        when(repository.find(any(), any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(rows);
        var reader = auth("CASH_READ");
        var hidden = service.list(null, null, null, null, null, null, 10, null, reader).items();
        assertThat(hidden).extracting(CashAlertView::type).containsExactly("OPENING", "CLOSING");
        assertThat(hidden.get(1).attemptNumber()).isEqualTo(1);
        assertThat(hidden.get(1).sessionClosed()).isFalse();
        assertThat(hidden).allSatisfy(item -> {
            assertThat(item.expectedFund()).isNull();
            assertThat(item.countedFund()).isNull();
            assertThat(item.difference()).isNull();
        });
        assertThat(service.list(null, null, null, null, null, null, 10, null, accountant).items()).isEqualTo(rows);
        assertThatThrownBy(() -> service.review(rows.getFirst().id(), "OPENING", "Revisado", 0, reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 10, null, auth("VENTA")))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).review(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void detailReturnsBothAttemptsAndRedactsEveryAmountForCashReaders() {
        var row = alert("CLOSING", clock.instant());
        var attempts = List.of(new CashAlertAttemptView(UUID.randomUUID(),1,clock.instant().minusSeconds(30),
                row.userId(),"seller","Seller",new BigDecimal("20"),new BigDecimal("15"),new BigDecimal("-5"),false),
                new CashAlertAttemptView(row.id(),2,clock.instant(),row.userId(),"seller","Seller",
                        new BigDecimal("20"),new BigDecimal("20"),BigDecimal.ZERO,true));
        when(repository.findById(storeId,row.id(),"CLOSING")).thenReturn(Optional.of(row));
        when(repository.findAttempts(storeId,row.sessionId())).thenReturn(attempts);
        var detail = service.detail(row.id(),"CLOSING",accountant);
        assertThat(detail.alert()).isEqualTo(row);
        assertThat(detail.attempts()).isEqualTo(attempts);
        var hidden = service.detail(row.id(),"CLOSING",auth("CASH_READ"));
        assertThat(hidden.alert().difference()).isNull();
        assertThat(hidden.alert().expectedFund()).isNull();
        assertThat(hidden.alert().countedFund()).isNull();
        assertThat(hidden.attempts()).extracting(CashAlertAttemptView::attemptNumber).containsExactly(1,2);
        assertThat(hidden.attempts()).allSatisfy(a -> {
            assertThat(a.expectedFund()).isNull();
            assertThat(a.countedFund()).isNull();
            assertThat(a.difference()).isNull();
        });
        assertThat(hidden.attempts()).extracting(CashAlertAttemptView::sessionClosed).containsExactly(false,true);
    }

    @Test
    void openingDetailDoesNotLoadClosingAttemptsAndUnknownOrUnauthorizedDetailsAreRejected() {
        var row = alert("OPENING",clock.instant());
        when(repository.findById(storeId,row.id(),"OPENING")).thenReturn(Optional.of(row));
        assertThat(service.detail(row.id(),"OPENING",accountant).attempts()).isEmpty();
        assertThatThrownBy(() -> service.detail(row.id(),"CLOSING",accountant))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> service.detail(row.id(),"INVALID",accountant)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.detail(row.id(),"OPENING",auth("VENTA"))).isInstanceOf(AccessDeniedException.class);
        verify(repository,never()).findAttempts(any(),any());
    }

    @Test
    void reviewPreservesSourceAndStoreAndRejectsStaleVersion() {
        var row = alert("CLOSING", clock.instant());
        var user = mock(UserAccount.class);
        var reviewer = UUID.randomUUID();
        when(user.getId()).thenReturn(reviewer);
        when(organization.currentUser(accountant)).thenReturn(user);
        when(repository.findById(storeId, row.id(), "CLOSING")).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.review(row.id(), "CLOSING", "  Revisado  ", 4, accountant))
                .isInstanceOf(IllegalStateException.class);
        verify(repository).review(storeId, row.id(), "CLOSING", reviewer, clock.instant(), "Revisado", 4);
        when(repository.review(storeId, row.id(), "CLOSING", reviewer, clock.instant(), "Revisado", 0)).thenReturn(true);
        assertThat(service.review(row.id(), "CLOSING", "Revisado", 0, accountant)).isEqualTo(row);
    }

    @Test
    void malformedInputsNeverReachRepository() {
        assertThatThrownBy(() -> service.list(null, null, null, null, "UNKNOWN", null, 1, null, accountant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(null, null, null, null, null, "UNKNOWN", 1, null, accountant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 1, "bad", accountant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.review(UUID.randomUUID(), null, "Revisado", 0, accountant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.review(UUID.randomUUID(), "CLOSING", " ", 0, accountant))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static UsernamePasswordAuthenticationToken auth(String authority) {
        return new UsernamePasswordAuthenticationToken("operator", "token", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static CashAlertView alert(String type, Instant at) {
        return new CashAlertView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TPV",
                UUID.randomUUID(), "seller", "Seller", type, at, type.equals("CLOSING") ? 1 : null,
                type.equals("CLOSING") ? false : null, BigDecimal.TEN, BigDecimal.ONE,
                new BigDecimal("-9"), "PENDING", null, null, null, null, null, 0);
    }
}

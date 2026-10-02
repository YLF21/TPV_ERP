package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class CashOpeningAlertServiceTest {
    private final CashOpeningAlertRepository repository=mock(CashOpeningAlertRepository.class);
    private final CurrentOrganization organization=mock(CurrentOrganization.class);
    private final CashPermissionService permissions=mock(CashPermissionService.class);
    private final Store store=mock(Store.class);
    private final UUID storeId=UUID.randomUUID();
    private final Clock clock=Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"),ZoneOffset.UTC);
    private final CashOpeningAlertService service=new CashOpeningAlertService(repository,organization,permissions,clock);
    private final UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("operator","token");

    CashOpeningAlertServiceTest() {
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
    }

    @Test
    void paginationPreservesSubMillisecondTimestampAndStoreTimezone() {
        var row=alert(Instant.parse("2026-07-31T09:00:00.123456Z"));
        when(repository.find(any(),any(),any(),any(),any(),any(),any(),anyInt())).thenReturn(List.of(row,row));
        when(repository.pendingCount(storeId)).thenReturn(8L);
        var page=service.list(LocalDate.parse("2026-07-31"),LocalDate.parse("2026-07-31"),null,null,"PENDING",1,null,auth);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.pendingCount()).isEqualTo(8);
        service.list(null,null,null,null,null,1,page.nextCursor(),auth);
        var cursor=ArgumentCaptor.forClass(CashOpeningAlertRepository.Cursor.class);
        verify(repository).find(eq(storeId),isNull(),isNull(),isNull(),isNull(),isNull(),cursor.capture(),eq(2));
        assertThat(cursor.getValue().at()).isEqualTo(row.openedAt());
        verify(repository).find(eq(storeId),eq(Instant.parse("2026-07-30T23:00:00Z")),
                eq(Instant.parse("2026-07-31T23:00:00Z")),isNull(),isNull(),eq("PENDING"),isNull(),eq(2));
    }

    @Test
    void reviewRejectsStaleVersionAndKeepsReviewScopedToCurrentStore() {
        var row=alert(Instant.now());
        var user=mock(UserAccount.class);var reviewerId=UUID.randomUUID();
        when(user.getId()).thenReturn(reviewerId);
        when(organization.currentUser(auth)).thenReturn(user);
        when(repository.findById(storeId,row.id())).thenReturn(Optional.of(row));
        assertThatThrownBy(()->service.review(row.id(),"Revisado",4,auth)).isInstanceOf(IllegalStateException.class);
        verify(permissions).requireAccountingPermission(auth);
        verify(repository).review(storeId,row.id(),reviewerId,clock.instant(),"Revisado",4);
    }

    @Test
    void malformedPaginationAndReviewInputNeverMutateEvidence() {
        assertThatThrownBy(()->service.list(null,null,null,null,"UNKNOWN",1,null,auth)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(null,null,null,null,null,1,"bad cursor",auth)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.review(UUID.randomUUID()," ",0,auth)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void cashReaderSeesEvidenceMetadataWhileOnlyAccountingAndAdminSeeAmounts() {
        var row = alert(clock.instant());
        when(repository.find(any(), any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(row));
        var service = new CashOpeningAlertService(repository, organization,
                new CashPermissionService(null, null, organization), clock);
        var reader = new UsernamePasswordAuthenticationToken("reader", "token", List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("CASH_READ")));
        var item = service.list(null, null, null, null, null, 10, null, reader).items().getFirst();
        assertThat(item.id()).isEqualTo(row.id());
        assertThat(item.status()).isEqualTo(row.status());
        assertThat(item.openedAt()).isEqualTo(row.openedAt());
        assertThat(item.expectedFund()).isNull();
        assertThat(item.countedFund()).isNull();
        assertThat(item.difference()).isNull();
        for (var authority : List.of("ROLE_ADMIN", "GESTION_CUENTAS")) {
            var auth = new UsernamePasswordAuthenticationToken("operator", "token", List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(authority)));
            assertThat(service.list(null, null, null, null, null, 10, null, auth).items()).containsExactly(row);
        }
    }

    private CashOpeningAlertView alert(Instant at) {
        var id=UUID.randomUUID();
        return new CashOpeningAlertView(id,id,UUID.randomUUID(),"TPV",UUID.randomUUID(),"seller","Seller",at,
                BigDecimal.TEN,BigDecimal.ONE,new BigDecimal("-9"),"PENDING",null,null,null,null,null,0);
    }
}

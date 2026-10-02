package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class CashTimelineServiceTest {
    @Test
    void omittedDateUsesStoreDayAndBlindPermissionHidesBalances() {
        var repository=mock(CashTimelineRepository.class);var organization=mock(CurrentOrganization.class);
        var terminals=mock(TerminalRepository.class);var permissions=mock(CashPermissionService.class);
        var store=mock(Store.class);var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();
        var auth=new UsernamePasswordAuthenticationToken("seller","token");
        when(organization.currentStore()).thenReturn(store);when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(terminals.findByIdAndTiendaId(terminalId,storeId)).thenReturn(Optional.of(mock(Terminal.class)));
        when(repository.find(any(),any(),any(),any())).thenReturn(List.of());
        var service=new CashTimelineService(repository,organization,terminals,permissions,
                Clock.fixed(Instant.parse("2026-07-31T23:30:00Z"),ZoneOffset.UTC));
        var result=service.timeline(terminalId,null,auth);
        assertThat(result.businessDate()).isEqualTo(LocalDate.parse("2026-08-01"));
        verify(repository).find(storeId,terminalId,Instant.parse("2026-07-31T23:00:00Z"),Instant.parse("2026-08-01T23:00:00Z"));
        var foreignTerminal=UUID.randomUUID();
        assertThatThrownBy(()->service.timeline(foreignTerminal,null,auth)).isInstanceOf(IllegalArgumentException.class);
        verify(repository,never()).find(eq(storeId),eq(foreignTerminal),any(),any());
    }
    @Test
    void financialVisibilityIsAppliedToEveryEventUsingActualCashPermissions() {
        var repository = mock(CashTimelineRepository.class);
        var organization = mock(CurrentOrganization.class);
        var terminals = mock(TerminalRepository.class);
        var permissions = new CashPermissionService(null, null, organization);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(terminals.findByIdAndTiendaId(terminalId, storeId)).thenReturn(Optional.of(mock(Terminal.class)));
        var events = List.of("OPENING", "ENTRADA", "RETIRADA", "CLOSING").stream()
                .map(action -> new CashTimelineView.Item(action, Instant.parse("2026-07-31T12:00:00Z"),
                        UUID.randomUUID(), "seller", "Seller", action, "Concept", java.math.BigDecimal.TEN,
                        java.math.BigDecimal.TEN, "Original reference", UUID.randomUUID(),
                        action.equals("CLOSING") ? CashSessionStatus.CERRADA : CashSessionStatus.ABIERTA,
                        "Document reference")).toList();
        when(repository.find(any(), any(), any(), any())).thenReturn(events);
        var service = new CashTimelineService(repository, organization, terminals, permissions,
                Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC));
        for (var authority : List.of("VENTA", "CASH_READ", "CASH_OPERATE")) {
            var auth = new UsernamePasswordAuthenticationToken("operator", "token",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(authority)));
            var result = service.timeline(terminalId, null, auth);
            assertThat(result.items()).hasSameSizeAs(events).allSatisfy(item -> {
                assertThat(item.amount()).isNull();
                assertThat(item.balance()).isNull();
                assertThat(item.reference()).isEqualTo("Original reference");
                assertThat(item.sourceReference()).isEqualTo("Document reference");
                assertThat(item.cashState()).isEqualTo(item.action().equals("CLOSING")
                        ? CashSessionStatus.CERRADA : CashSessionStatus.ABIERTA);
            });
        }
        for (var authority : List.of("ROLE_ADMIN", "GESTION_CUENTAS")) {
            var auth = new UsernamePasswordAuthenticationToken("operator", "token",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(authority)));
            assertThat(service.timeline(terminalId, null, auth).items()).containsExactlyElementsOf(events);
        }
    }

}

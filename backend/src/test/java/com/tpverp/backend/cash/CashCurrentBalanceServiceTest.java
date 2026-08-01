package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class CashCurrentBalanceServiceTest {

    @Test
    void readsOnlyTheCurrentStoreAndRequiresCashReportPermission() {
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var now = Instant.parse("2026-08-01T10:15:00Z");
        var repository = mock(CashCurrentBalanceQueryRepository.class);
        var organization = mock(CurrentOrganization.class);
        var permissions = mock(CashPermissionService.class);
        var authentication = mock(Authentication.class);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        when(repository.findCurrentBalances(storeId)).thenReturn(List.of(new CashCurrentBalanceView(
                terminalId, "TPV 1", CashCurrentBalanceStatus.ABIERTA,
                UUID.randomUUID(), "CAJERO", "cajero", now.minusSeconds(3600),
                new BigDecimal("135.00"), now.minusSeconds(15))));
        var service = new CashCurrentBalanceService(
                repository, organization, permissions, Clock.fixed(now, ZoneOffset.UTC));

        var result = service.current(authentication);

        verify(permissions).requireReportPermission(authentication);
        verify(repository).findCurrentBalances(storeId);
        assertThat(result.asOf()).isEqualTo(now);
        assertThat(result.timezone()).isEqualTo("Atlantic/Canary");
        assertThat(result.terminals()).singleElement()
                .extracting(CashCurrentBalanceView::expectedCash)
                .isEqualTo(new BigDecimal("135.00"));
    }
}

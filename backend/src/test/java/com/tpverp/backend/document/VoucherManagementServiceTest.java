package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

class VoucherManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Test
    void recordsAReprintWithoutTerminalForAManagementSession() {
        var voucherRepository = mock(VoucherRepository.class);
        var configurationRepository = mock(StoreVoucherConfigurationRepository.class);
        var eventRepository = mock(VoucherManagementEventRepository.class);
        var printing = mock(VoucherPrintService.class);
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var audit = mock(AuditService.class);
        var authentication = mock(Authentication.class);
        var store = mock(Store.class);
        var operator = mock(UserAccount.class);
        var storeId = UUID.randomUUID();
        var operatorId = UUID.randomUUID();
        var voucher = new Voucher(
                storeId, "V-001", new BigDecimal("25.00"),
                List.of("T-001"), NOW);

        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(operator);
        when(operator.getId()).thenReturn(operatorId);
        when(operator.getUserName()).thenReturn("Administrador");
        when(voucherRepository.findByTiendaIdAndCodeIgnoreCase(storeId, "V-001"))
                .thenReturn(Optional.of(voucher));
        when(currentTerminal.terminalId(authentication))
                .thenThrow(new IllegalStateException("No hay una terminal resoluble"));

        var service = new VoucherManagementService(
                voucherRepository, configurationRepository, eventRepository,
                printing, organization, currentTerminal, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(eventRepository.findAllByVoucher_IdOrderByOccurredAtDesc(voucher.id()))
                .thenReturn(List.of());

        var detail = service.recordPrintResult("V-001", true, authentication);

        var event = ArgumentCaptor.forClass(VoucherManagementEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(detail.voucher().code()).isEqualTo("V-001");
        assertThat(detail.events()).isEmpty();
        assertThat(event.getValue().type())
                .isEqualTo(VoucherManagementEventType.REPRINTED);
        assertThat(event.getValue().terminalId()).isNull();
        verify(audit).record(
                eq("VOUCHER_REPRINTED"),
                eq(AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(
                        details -> voucher.id().toString().equals(details.get("voucherId"))
                                && operatorId.toString().equals(details.get("operatorId"))
                                && !details.containsKey("terminalId")));
    }
}

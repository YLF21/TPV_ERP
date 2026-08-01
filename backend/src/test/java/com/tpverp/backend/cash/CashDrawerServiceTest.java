package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class CashDrawerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    private TerminalRepository terminals;
    private CurrentOrganization organization;
    private SaleOperationSecurityService operationSecurity;
    private ControlAlertDetectionService controlAlerts;
    private AuditService audit;
    private CashDrawerService service;
    private Store store;
    private Terminal terminal;
    private UserAccount operator;
    private UserAccount authorizer;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        terminals = mock(TerminalRepository.class);
        organization = mock(CurrentOrganization.class);
        operationSecurity = mock(SaleOperationSecurityService.class);
        controlAlerts = mock(ControlAlertDetectionService.class);
        audit = mock(AuditService.class);
        service = new CashDrawerService(
                terminals,
                organization,
                operationSecurity,
                controlAlerts,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        store = mock(Store.class);
        terminal = mock(Terminal.class);
        operator = mock(UserAccount.class);
        authorizer = mock(UserAccount.class);
        authentication = new UsernamePasswordAuthenticationToken(operator, "token");
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(terminal.getId()).thenReturn(UUID.randomUUID());
        when(terminal.getNombre()).thenReturn("01");
        when(terminal.isActiva()).thenReturn(true);
        when(terminal.isAprobada()).thenReturn(true);
        when(operator.getId()).thenReturn(UUID.randomUUID());
        when(operator.getUserName()).thenReturn("OPERADOR");
        when(authorizer.getId()).thenReturn(UUID.randomUUID());
        when(authorizer.getUserName()).thenReturn("ENCARGADO");
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(operator);
        when(terminals.findByIdAndTiendaId(terminal.getId(), store.getId()))
                .thenReturn(Optional.of(terminal));
        when(operationSecurity.authorize(
                eq(SaleOperationCode.OPEN_CASH_DRAWER), any(), any(), eq(authentication)))
                .thenReturn(new OperationalPermissionAuthorizationService.Authorization(
                        operator, authorizer, true));
    }

    @Test
    void recordsAuthorizationAndSuccessfulHardwareOpening() {
        var authorization = service.authorize(
                terminal.getId(), "encargado", "1234", authentication);

        var result = service.complete(
                authorization.operationId(), true, null, null, authentication);

        assertThat(result.opened()).isTrue();
        var terminalId = terminal.getId();
        var authorizerId = authorizer.getId();
        verify(audit).record(eq("CASH_DRAWER_OPEN_AUTHORIZED"), eq(AuditResult.EXITO), any());
        verify(audit).record(eq("CASH_DRAWER_OPENED"), eq(AuditResult.EXITO), any());
        verify(operationSecurity).authorize(
                SaleOperationCode.OPEN_CASH_DRAWER,
                "encargado",
                "1234",
                authentication);
        verify(controlAlerts).detectCashDrawerOpened(
                eq(authorization.operationId()),
                eq(terminalId),
                eq("01"),
                eq(authorizerId),
                eq("ENCARGADO"),
                eq(true),
                eq(authentication));
    }

    @Test
    void recordsHardwareFailureWithoutCreatingControlAlert() {
        var authorization = service.authorize(
                terminal.getId(), "encargado", "1234", authentication);

        var result = service.complete(
                authorization.operationId(),
                false,
                "CASH_DRAWER_UNAVAILABLE",
                "Cajon no configurado",
                authentication);

        assertThat(result.opened()).isFalse();
        verify(audit).record(eq("CASH_DRAWER_OPEN_FAILED"), eq(AuditResult.FALLO), any());
        verify(controlAlerts, never()).detectCashDrawerOpened(
                any(), any(), any(), any(), any(), eq(true), any());
    }
}

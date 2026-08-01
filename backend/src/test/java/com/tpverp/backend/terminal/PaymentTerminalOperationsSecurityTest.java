package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class PaymentTerminalOperationsSecurityTest {

    private final UUID storeId = UUID.randomUUID();
    private final UUID terminalId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-07-30T10:00:00Z");
    private final CardTerminalConfiguration configuration = new CardTerminalConfiguration(
            terminalId,
            storeId,
            PaymentCardMode.INTEGRATED,
            PaymentTerminalProvider.PAYTEF,
            true,
            true,
            "TPV",
            "config-ref",
            4,
            "a".repeat(64),
            Map.of());

    private PaymentTerminalOperationRepository operations;
    private PaymentTerminalAdjustmentService adjustments;
    private CardTerminalConfigurationReader configurations;
    private CardTerminalGateway gateway;
    private DocumentService documents;
    private SaleOperationSecurityService operationSecurity;
    private AuditService audit;
    private Authentication authentication;
    private PaymentTerminalOperationsService service;

    @BeforeEach
    void setUp() {
        operations = mock(PaymentTerminalOperationRepository.class);
        adjustments = mock(PaymentTerminalAdjustmentService.class);
        configurations = mock(CardTerminalConfigurationReader.class);
        gateway = mock(CardTerminalGateway.class);
        documents = mock(DocumentService.class);
        operationSecurity = mock(SaleOperationSecurityService.class);
        audit = mock(AuditService.class);
        authentication = mock(Authentication.class);

        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var operator = user("CAJERO");
        var authorizer = user("ENCARGADO");
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(configurations.required(terminalId)).thenReturn(configuration);
        when(gateway.supports(configuration.provider(), true)).thenReturn(true);
        when(operationSecurity.authorize(
                any(SaleOperationCode.class),
                any(),
                any(),
                eq(authentication)))
                .thenReturn(new Authorization(operator, authorizer, true));

        service = new PaymentTerminalOperationsService(
                operations,
                adjustments,
                mock(PaymentTerminalOperationService.class),
                configurations,
                List.of(gateway),
                Clock.fixed(now, ZoneOffset.UTC),
                organization,
                mock(PaymentTerminalReceiptRepository.class),
                mock(PaymentTerminalReconciliationService.class),
                documents,
                operationSecurity,
                audit);
    }

    @Test
    void directVoidAuthorizesBeforeReservationAndReplayDoesNotReachGateway() {
        when(gateway.capabilities()).thenReturn(Set.of(PaymentTerminalCapability.VOID));
        var original = approvedCharge(false);
        var adjustment = mock(PaymentTerminalOperation.class);
        when(adjustment.getStatus()).thenReturn(PaymentTerminalOperationStatus.CANCELLED);
        when(adjustments.reserveVoid(
                any(), any(), any(), any(), any(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(adjustment);

        var operationId = UUID.randomUUID();
        var result = service.voidAuthorization(
                original.getId(),
                operationId,
                "void-key",
                "encargado",
                "1234",
                authentication);

        assertThat(result).isSameAs(adjustment);
        var order = inOrder(operationSecurity, adjustments);
        order.verify(operationSecurity).authorize(
                SaleOperationCode.PAYMENT_TERMINAL_VOID,
                "encargado",
                "1234",
                authentication);
        order.verify(adjustments).reserveVoid(
                eq(operationId),
                eq(original.getId()),
                eq(terminalId),
                eq(storeId),
                eq(configuration.provider()),
                eq("void-key"),
                anyString(),
                eq(configuration.configurationHash()),
                eq(configuration.configurationVersion()),
                eq(now));
        verify(gateway, never()).voidAuthorization(any(), any());
        verify(audit).record(
                eq("PAYMENT_TERMINAL_VOID_AUTHORIZED"),
                any(),
                org.mockito.ArgumentMatchers.argThat(details ->
                        "CAJERO".equals(details.get("operatorUsername"))
                                && "ENCARGADO".equals(details.get("authorizerUsername"))
                                && !details.containsKey("authorizerPassword")));
    }

    @Test
    void directRefundAuthorizesButMixedTicketRefundDoesNotApplyTerminalPolicyAgain() {
        when(gateway.capabilities()).thenReturn(Set.of(PaymentTerminalCapability.REFUND));
        var original = approvedCharge(true);
        var adjustment = mock(PaymentTerminalOperation.class);
        when(adjustment.getStatus()).thenReturn(PaymentTerminalOperationStatus.TIMEOUT);
        when(adjustments.reserveRefund(
                any(), any(), any(), any(), any(), anyString(), anyString(), any(),
                anyString(), anyLong(), any(), anyString(), eq(false)))
                .thenReturn(adjustment);

        var directOperationId = UUID.randomUUID();
        var result = service.refund(
                original.getId(),
                directOperationId,
                "refund-key",
                new BigDecimal("2.00"),
                List.of(),
                "encargado",
                "1234",
                authentication);

        assertThat(result).isSameAs(adjustment);
        var order = inOrder(operationSecurity, adjustments);
        order.verify(operationSecurity).authorize(
                SaleOperationCode.PAYMENT_TERMINAL_REFUND,
                "encargado",
                "1234",
                authentication);
        order.verify(adjustments).reserveRefund(
                eq(directOperationId),
                eq(original.getId()),
                eq(terminalId),
                eq(storeId),
                eq(configuration.provider()),
                eq("refund-key"),
                anyString(),
                eq(new BigDecimal("2.00")),
                eq(configuration.configurationHash()),
                eq(configuration.configurationVersion()),
                eq(now),
                eq(""),
                eq(false));
        verify(gateway, never()).refund(any(), any());

        org.mockito.Mockito.clearInvocations(operationSecurity, adjustments);
        when(adjustments.reserveRefund(
                any(), any(), any(), any(), any(), anyString(), anyString(), any(),
                anyString(), anyLong(), any(), anyString(), eq(true)))
                .thenReturn(adjustment);

        service.refundPaymentOnly(
                original.getId(),
                UUID.randomUUID(),
                "ticket-return-key",
                new BigDecimal("1.00"));

        verifyNoInteractions(operationSecurity);
    }

    private PaymentTerminalOperation approvedCharge(boolean withDocument) {
        var original = PaymentTerminalOperation.reserve(
                UUID.randomUUID(),
                terminalId,
                storeId,
                configuration.provider(),
                PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.CHARGE,
                null,
                UUID.randomUUID().toString(),
                "b".repeat(64),
                new BigDecimal("10.00"),
                configuration.configurationHash(),
                configuration.configurationVersion(),
                now);
        original.markSent("GATEWAY_SEND", now);
        original.approve("reference", "authorization", now);
        if (withDocument) {
            original.linkDocument(UUID.randomUUID(), UUID.randomUUID(), now);
        }
        when(operations.findById(original.getId())).thenReturn(Optional.of(original));
        when(operations.findByTerminalIdAndIdempotencyKey(
                eq(terminalId), anyString())).thenReturn(Optional.of(mock(PaymentTerminalOperation.class)));
        return original;
    }

    private static UserAccount user(String name) {
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getUserName()).thenReturn(name);
        return user;
    }
}

package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import com.tpverp.backend.terminal.PaymentTerminalOperationsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class TicketCancellationServiceTest {

    @Test
    void completedRetryRebuildsVoucherResultWithoutOpeningDrawerAgain() {
        var ticketId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var authorizerId = UUID.randomUUID();
        var ticket = mock(CommercialDocument.class);
        when(ticket.getEstado()).thenReturn(DocumentStatus.ANULADO);

        var operation = mock(TicketCancellationOperation.class);
        when(operation.getTicketId()).thenReturn(ticketId);
        when(operation.getId()).thenReturn(requestId);
        when(operation.getReason()).thenReturn("Error de cobro");
        when(operation.getOperatorUserId()).thenReturn(authorizerId);
        when(operation.getAuthorizerUserId()).thenReturn(authorizerId);
        when(operation.getStatus()).thenReturn(TicketCancellationStatus.COMPLETED);
        doNothing().when(operation).requireCompatible(any(), any());

        var authorizer = mock(UserAccount.class);
        when(authorizer.getId()).thenReturn(authorizerId);
        var authorization = new OperationalPermissionAuthorizationService.Authorization(
                authorizer, authorizer, false);
        var operationSecurity = mock(SaleOperationSecurityService.class);
        when(operationSecurity.authorize(
                        org.mockito.ArgumentMatchers.eq(SaleOperationCode.CANCEL_TICKET),
                        any(),
                        any(),
                        any()))
                .thenReturn(authorization);

        var documents = mock(DocumentService.class);
        when(documents.find(ticketId)).thenReturn(ticket);
        when(documents.findDetailed(ticketId)).thenReturn(ticket);
        var operations = mock(TicketCancellationOperationRepository.class);
        when(operations.findById(requestId)).thenReturn(Optional.of(operation));

        var restoredVoucher = mock(Voucher.class);
        when(restoredVoucher.code()).thenReturn("VALE-REST");
        var invalidatedVoucher = mock(Voucher.class);
        when(invalidatedVoucher.code()).thenReturn("VALE-INVALID");
        var restoredEvent = mock(VoucherEvent.class);
        when(restoredEvent.getType()).thenReturn(VoucherEventType.RESTORED);
        when(restoredEvent.getVoucher()).thenReturn(restoredVoucher);
        when(restoredEvent.getAmount()).thenReturn(new BigDecimal("12.50"));
        var invalidatedEvent = mock(VoucherEvent.class);
        when(invalidatedEvent.getType()).thenReturn(VoucherEventType.INVALIDATED);
        when(invalidatedEvent.getVoucher()).thenReturn(invalidatedVoucher);
        var voucherEvents = mock(VoucherEventRepository.class);
        when(voucherEvents.findAllByDocumentIdOrderByOccurredAtAsc(ticketId))
                .thenReturn(List.of(restoredEvent, invalidatedEvent));
        var voucherPrinting = mock(VoucherPrintService.class);
        var printDocument = new VoucherPrintService.PrintedVoucher(
                "VALE-REST",
                "001-000001",
                new BigDecimal("12.50"),
                Instant.parse("2026-07-30T10:00:00Z"),
                null,
                "T-ORIGEN",
                List.of(),
                null,
                new VoucherPrintService.RenderedContent("application/pdf", "JVBERi0="),
                new VoucherPrintService.RenderedContent("image/png", "iVBORw0="));
        when(voucherPrinting.render(restoredVoucher)).thenReturn(printDocument);
        var users = mock(UserAccountRepository.class);
        when(authorizer.getUserName()).thenReturn("ADMIN");
        when(users.findById(authorizerId)).thenReturn(Optional.of(authorizer));

        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        var service = new TicketCancellationService(
                documents,
                mock(CommercialDocumentRepository.class),
                operations,
                voucherEvents,
                voucherPrinting,
                operationSecurity,
                users,
                mock(PaymentTerminalOperationsService.class),
                mock(CurrentOrganization.class),
                mock(CurrentTerminal.class),
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC),
                transactionManager);

        var result = service.cancel(new TicketCancellationService.CancellationCommand(
                requestId,
                ticketId,
                "Error de cobro",
                null,
                "1234",
                Map.of()), mock(Authentication.class));

        assertThat(result.ticket()).isSameAs(ticket);
        assertThat(result.restoredVouchers())
                .containsExactly(new TicketCancellationService.RestoredVoucher(
                        "VALE-REST", new BigDecimal("12.50"), printDocument));
        assertThat(result.invalidatedVoucherCodes()).containsExactly("VALE-INVALID");
        assertThat(result.openCashDrawer()).isFalse();
        assertThat(result.receipt().operationId()).isEqualTo(requestId);
        assertThat(result.receipt().originalTicketNumber()).isNull();
        assertThat(result.receipt().reason()).isEqualTo("Error de cobro");
        assertThat(result.receipt().operatorUsername()).isEqualTo("ADMIN");
        assertThat(result.receipt().authorizerUsername()).isEqualTo("ADMIN");
        assertThat(result.receipt().delegated()).isFalse();
        verify(operationSecurity).authorize(
                org.mockito.ArgumentMatchers.eq(SaleOperationCode.CANCEL_TICKET),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("1234"),
                org.mockito.ArgumentMatchers.any(Authentication.class));
        verify(transactionManager).commit(any());
    }
}

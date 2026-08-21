package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.document.template.TicketCancellationJasperRenderer;
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
    void rebuildsALegacyCancellationReceiptWithoutAnOperationRecord() {
        var ticketId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var ticket = mock(CommercialDocument.class);
        when(ticket.getEstado()).thenReturn(DocumentStatus.ANULADO);
        when(ticket.getNumero()).thenReturn("T-LEGACY");
        when(ticket.getConfirmadoEn()).thenReturn(Instant.parse("2026-07-18T13:06:00Z"));
        when(ticket.getAnuladoEn()).thenReturn(Instant.parse("2026-07-31T10:00:00Z"));
        when(ticket.getAnuladoPor()).thenReturn(userId);
        when(ticket.getMotivoAnulacion()).thenReturn("Anulación importada");
        when(ticket.getTotal()).thenReturn(new BigDecimal("6.05"));
        when(ticket.getPagos()).thenReturn(List.of());

        var documents = mock(DocumentService.class);
        when(documents.findDetailed(ticketId)).thenReturn(ticket);
        var operations = mock(TicketCancellationOperationRepository.class);
        when(operations.findFirstByTicketIdAndStoreIdAndStatusOrderByCompletedAtDesc(
                ticketId, storeId, TicketCancellationStatus.COMPLETED))
                .thenReturn(Optional.empty());
        var user = mock(UserAccount.class);
        when(user.getUserName()).thenReturn("ADMIN");
        var users = mock(UserAccountRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        var renderer = mock(TicketCancellationJasperRenderer.class);
        when(renderer.render(any(), any())).thenReturn(
                new TicketCancellationJasperRenderer.RenderedCancellation(
                        "%PDF legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "PNG legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        var service = new TicketCancellationService(
                documents, mock(CommercialDocumentRepository.class), operations,
                mock(VoucherEventRepository.class), mock(VoucherPrintService.class), renderer,
                mock(SaleOperationSecurityService.class), users,
                mock(PaymentTerminalOperationsService.class), organization,
                mock(CurrentTerminal.class),
                Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC),
                transactionManager);

        var receipt = service.cancellationReceipt(ticketId);

        assertThat(receipt.originalTicketNumber()).isEqualTo("T-LEGACY");
        assertThat(receipt.reason()).isEqualTo("Anulación importada");
        assertThat(receipt.operatorUsername()).isEqualTo("ADMIN");
        assertThat(receipt.authorizerUsername()).isEqualTo("ADMIN");
        assertThat(receipt.renderedPdf().contentType()).isEqualTo("application/pdf");
        assertThat(receipt.operationId()).isEqualTo(UUID.nameUUIDFromBytes(
                ("legacy-cancellation:" + ticketId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void rebuildsTheCancellationReceiptForPrinting() {
        var ticketId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var ticket = mock(CommercialDocument.class);
        when(ticket.getEstado()).thenReturn(DocumentStatus.ANULADO);
        when(ticket.getNumero()).thenReturn("T-ANULADO");
        when(ticket.getConfirmadoEn()).thenReturn(Instant.parse("2026-08-20T10:00:00Z"));
        when(ticket.getAnuladoEn()).thenReturn(Instant.parse("2026-08-20T10:05:00Z"));
        when(ticket.getTotal()).thenReturn(new BigDecimal("6.05"));
        when(ticket.getPagos()).thenReturn(List.of());

        var operation = mock(TicketCancellationOperation.class);
        when(operation.getId()).thenReturn(operationId);
        when(operation.getReason()).thenReturn("Error de cobro");
        when(operation.getOperatorUserId()).thenReturn(userId);
        when(operation.getAuthorizerUserId()).thenReturn(userId);

        var documents = mock(DocumentService.class);
        when(documents.findDetailed(ticketId)).thenReturn(ticket);
        var operations = mock(TicketCancellationOperationRepository.class);
        when(operations.findFirstByTicketIdAndStoreIdAndStatusOrderByCompletedAtDesc(
                ticketId, storeId, TicketCancellationStatus.COMPLETED))
                .thenReturn(Optional.of(operation));
        var user = mock(UserAccount.class);
        when(user.getUserName()).thenReturn("ADMIN");
        var users = mock(UserAccountRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        var renderer = mock(TicketCancellationJasperRenderer.class);
        when(renderer.render(any(), any())).thenReturn(
                new TicketCancellationJasperRenderer.RenderedCancellation(
                        "%PDF cancellation".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "PNG cancellation".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        var service = new TicketCancellationService(
                documents, mock(CommercialDocumentRepository.class), operations,
                mock(VoucherEventRepository.class), mock(VoucherPrintService.class), renderer,
                mock(SaleOperationSecurityService.class), users,
                mock(PaymentTerminalOperationsService.class), organization,
                mock(CurrentTerminal.class),
                Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC),
                transactionManager);

        var receipt = service.cancellationReceipt(ticketId);

        assertThat(receipt.originalTicketNumber()).isEqualTo("T-ANULADO");
        assertThat(receipt.reason()).isEqualTo("Error de cobro");
        assertThat(receipt.operatorUsername()).isEqualTo("ADMIN");
        assertThat(receipt.renderedPdf().contentType()).isEqualTo("application/pdf");
        verify(operations).findFirstByTicketIdAndStoreIdAndStatusOrderByCompletedAtDesc(
                ticketId, storeId, TicketCancellationStatus.COMPLETED);
    }

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
        var cancellationPrinting = mock(TicketCancellationJasperRenderer.class);
        when(cancellationPrinting.render(any(), any())).thenReturn(
                new TicketCancellationJasperRenderer.RenderedCancellation(
                        "%PDF cancellation".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "PNG cancellation".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(mock(Store.class));
        var service = new TicketCancellationService(
                documents,
                mock(CommercialDocumentRepository.class),
                operations,
                voucherEvents,
                voucherPrinting,
                cancellationPrinting,
                operationSecurity,
                users,
                mock(PaymentTerminalOperationsService.class),
                organization,
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
        assertThat(result.receipt().renderedPdf().contentType())
                .isEqualTo("application/pdf");
        assertThat(java.util.Base64.getDecoder().decode(
                result.receipt().renderedPdf().base64()))
                .isEqualTo("%PDF cancellation".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        assertThat(result.receipt().ticketRenderedImage().contentType())
                .isEqualTo("image/png");
        verify(operationSecurity).authorize(
                org.mockito.ArgumentMatchers.eq(SaleOperationCode.CANCEL_TICKET),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("1234"),
                org.mockito.ArgumentMatchers.any(Authentication.class));
        verify(transactionManager).commit(any());
    }
}

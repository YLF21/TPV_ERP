package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.terminal.PaymentCardMode;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import com.tpverp.backend.terminal.PaymentTerminalProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RefundPaymentAvailabilityTest {

    @Test
    void calculatesRealRemainingBalancePerOriginalPaymentMethod() {
        var ticket = ticket();
        var cash = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", false);
        var card = new PaymentMethod(UUID.randomUUID(), "TARJETA", false);
        var cashPayment = payment(ticket, cash, 1, "41.00", null);
        var cardPayment = payment(ticket, card, 2, "41.00", PaymentCardMode.INTEGRATED);
        ticket.addPayment(cashPayment);
        ticket.addPayment(cardPayment);

        var session = SalePaymentSession.reserve(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "hash", "{}", new BigDecimal("82.00").negate());
        var reservedCash = session.addAllocation(
                UUID.randomUUID(), "cash-reserved", SalePaymentAllocationKind.CASH,
                new BigDecimal("6.00"), null, null);
        reservedCash.assignOriginalPaymentId(cashPayment.getId());
        reservedCash.approve(null, null, null);

        var tenders = Mockito.mock(RefundTenderRepository.class);
        when(tenders.refundedAmountByOriginalPaymentId(cashPayment.getId()))
                .thenReturn(new BigDecimal("5.00"));
        when(tenders.refundedAmountByOriginalPaymentId(cardPayment.getId()))
                .thenReturn(new BigDecimal("10.00"));

        var availability = RefundPaymentAvailability.calculate(
                ticket, tenders, session.getAllocations());

        assertThat(availability).extracting(RefundPaymentAvailability.View::kind)
                .containsExactly(
                        SalePaymentAllocationKind.CASH,
                        SalePaymentAllocationKind.INTEGRATED_CARD);
        assertThat(availability.getFirst().originalAmount()).isEqualByComparingTo("41.00");
        assertThat(availability.getFirst().refundedAmount()).isEqualByComparingTo("5.00");
        assertThat(availability.getFirst().reservedAmount()).isEqualByComparingTo("6.00");
        assertThat(availability.getFirst().availableAmount()).isEqualByComparingTo("30.00");
        assertThat(availability.get(1).availableAmount()).isEqualByComparingTo("31.00");
    }

    @Test
    void excludesExchangeCompensationBecauseItIsNotMoneyPaidByTheCustomer() {
        var ticket = ticket();
        var cash = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", false);
        var exchangeCompensation = new PaymentMethod(
                UUID.randomUUID(), PaymentMethodService.EXCHANGE_COMPENSATION_METHOD, false);
        var cashPayment = payment(ticket, cash, 1, "10.00", null);
        ticket.addPayment(cashPayment);
        ticket.addPayment(payment(ticket, exchangeCompensation, 2, "72.00", null));

        var tenders = Mockito.mock(RefundTenderRepository.class);
        when(tenders.refundedAmountByOriginalPaymentId(cashPayment.getId()))
                .thenReturn(BigDecimal.ZERO);
        var availability = RefundPaymentAvailability.calculate(ticket, tenders, List.of());

        assertThat(availability).singleElement().satisfies(view -> {
            assertThat(view.paymentMethod()).isEqualTo("EFECTIVO");
            assertThat(view.kind()).isEqualTo(SalePaymentAllocationKind.CASH);
            assertThat(view.availableAmount()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void exposesRemainingTransferBalanceAsARefundSource() {
        var ticket = ticket();
        var transfer = new PaymentMethod(UUID.randomUUID(), "TRANSFERENCIA", false);
        var transferPayment = payment(ticket, transfer, 1, "10.00", null);
        ticket.addPayment(transferPayment);

        var tenders = Mockito.mock(RefundTenderRepository.class);
        when(tenders.refundedAmountByOriginalPaymentId(transferPayment.getId()))
                .thenReturn(new BigDecimal("2.50"));

        var availability = RefundPaymentAvailability.calculate(ticket, tenders, List.of());

        assertThat(availability).singleElement().satisfies(view -> {
            assertThat(view.paymentMethod()).isEqualTo("TRANSFERENCIA");
            assertThat(view.kind()).isEqualTo(SalePaymentAllocationKind.TRANSFER);
            assertThat(view.availableAmount()).isEqualByComparingTo("7.50");
        });
    }

    private static CommercialDocument ticket() {
        var ticket = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 5), UUID.randomUUID(), BigDecimal.ZERO);
        ticket.addLine(new DocumentLine(
                ticket, UUID.randomUUID(), 1, BigDecimal.ONE, "P-1", "Product", null,
                new BigDecimal("82.00"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        return ticket;
    }

    private static DocumentPayment payment(
            CommercialDocument ticket,
            PaymentMethod method,
            int position,
            String amount,
            PaymentCardMode cardMode) {
        return new DocumentPayment(
                ticket, method, position, new BigDecimal(amount), position == 1,
                null, null, null, null, Instant.parse("2026-08-05T12:00:00Z"),
                cardMode,
                cardMode == PaymentCardMode.INTEGRATED
                        ? PaymentTerminalProvider.PAYTEF : null,
                PaymentTerminalOperationStatus.APPROVED,
                null,
                cardMode == PaymentCardMode.INTEGRATED ? UUID.randomUUID() : null);
    }
}

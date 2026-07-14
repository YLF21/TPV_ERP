package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SalePaymentSessionTest {
    @Test void coversExactlyOnlyFromApprovedAllocationsAndKeepsStableKeys() {
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("10.00"));
        var cash=session.addAllocation(UUID.randomUUID(),"cash-key",SalePaymentAllocationKind.CASH,new BigDecimal("2.00"),null,null);
        cash.approve(null,null,null);
        var card=session.addAllocation(UUID.randomUUID(),"card-key",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("8.00"),"PAYTEF","INTEGRATED");
        assertThat(session.approvedTotal()).isEqualByComparingTo("2.00");
        assertThat(session.isCovered()).isFalse();
        card.approve(card.getId(),"ref","auth");
        assertThat(session.isCovered()).isTrue();
        assertThatThrownBy(()->session.addAllocation(UUID.randomUUID(),"cash-key",SalePaymentAllocationKind.CASH,new BigDecimal("1.00"),null,null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsOverAllocationAndCannotCancelApprovedPartialSession() {
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("10.00"));
        var cash=session.addAllocation(UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("6.00"),null,null);cash.approve(null,null,null);
        assertThatThrownBy(()->session.addAllocation(UUID.randomUUID(),"too-much",SalePaymentAllocationKind.MANUAL_CARD,new BigDecimal("5.00"),null,"MANUAL")).isInstanceOf(IllegalArgumentException.class);
        session.cancel();
        assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COMPENSATION_REQUIRED);
        assertThatThrownBy(()->session.acknowledgeCompensation(" ",UUID.randomUUID())).hasMessage("compensation_note_required");
        session.acknowledgeCompensation("Efectivo devuelto y firmado",UUID.randomUUID());
        assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
    }

    @Test void finalizeIsIdempotentAndNeverReplacesTheFirstTicket() {
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("1.00"));
        session.addAllocation(UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("1.00"),null,null).approve(null,null,null);
        var first=UUID.randomUUID();session.finalizeWith(first,"T-1");session.finalizeWith(UUID.randomUUID(),"T-2");
        assertThat(session.getTicketId()).isEqualTo(first);assertThat(session.getTicketNumber()).isEqualTo("T-1");
    }

    @Test void cancellingAnUncertainIntegratedPaymentRequiresCompensation() {
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("10.00"));
        var card=session.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,
                new BigDecimal("10.00"),"PAYTEF","INTEGRATED");
        card.result(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.TIMEOUT,card.getId(),null,null,"incierto");

        session.cancel();

        assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COMPENSATION_REQUIRED);
    }

    @Test void lateApprovalNeverReactivatesACancelledSession() {
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("10.00"));
        var card=session.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,
                new BigDecimal("10.00"),"PAYTEF","INTEGRATED");
        card.result(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.DECLINED,card.getId(),null,null,"rechazado");
        session.cancel();

        card.result(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.APPROVED,card.getId(),"ref","auth","tarde");

        assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
        assertThat(card.getStatus()).isEqualTo(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.DECLINED);
    }

    @Test void lateResultNeverReopensCompensationRequiredOrFinalizedSession() {
        var compensating=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("10.00"));
        var uncertain=compensating.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,
                new BigDecimal("10.00"),"PAYTEF","INTEGRATED");
        uncertain.result(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.TIMEOUT,uncertain.getId(),null,null,"incierto");
        compensating.cancel();
        uncertain.result(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.APPROVED,uncertain.getId(),"late","auth","tarde");
        assertThat(compensating.getStatus()).isEqualTo(SalePaymentSessionStatus.COMPENSATION_REQUIRED);
        assertThat(uncertain.getStatus()).isEqualTo(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.TIMEOUT);

        var finalized=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("10.00"));
        var approved=finalized.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,
                new BigDecimal("10.00"),"PAYTEF","INTEGRATED");
        approved.approve(approved.getId(),"ref","auth");
        finalized.finalizeWith(UUID.randomUUID(),"T-1");
        approved.result(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.DECLINED,approved.getId(),null,null,"late");
        finalized.cancel();
        assertThat(finalized.getStatus()).isEqualTo(SalePaymentSessionStatus.FINALIZED);
        assertThat(approved.getStatus()).isEqualTo(com.tpverp.backend.terminal.PaymentTerminalOperationStatus.APPROVED);
    }

}

package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;

class SalePaymentSessionTest {
    @ParameterizedTest
    @EnumSource(value = PaymentTerminalOperationStatus.class, names = {"PENDING", "TIMEOUT", "APPROVED"})
    void discardsSimulatedUnfinishedAllocationsWithoutDeletingHistory(PaymentTerminalOperationStatus status) {
        var userId=UUID.randomUUID();
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),userId,"hash","{}",new BigDecimal("10.00"));
        var allocation=session.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,
                new BigDecimal("10.00"),"PAYTEF","INTEGRATED");
        allocation.result(status,allocation.getId(),"ref","auth","result");
        var allocationId=allocation.getId();

        session.discardSimulation("  application_shutdown  ",userId);

        assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
        assertThat(session.getAllocations()).singleElement().extracting(SalePaymentAllocation::getId).isEqualTo(allocationId);
        assertThat(session.getCompensationNote()).isEqualTo("application_shutdown");
        assertThat(session.getCompensationResolvedBy()).isEqualTo(userId);
        assertThat(session.getCompensationResolvedAt()).isNotNull();
    }

    @Test void discardsCoveredAndCompensationRequiredSimulatedSessions() {
        var userId=UUID.randomUUID();
        var covered=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),userId,"hash","{}",BigDecimal.TEN);
        covered.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED").approve(UUID.randomUUID(),"ref","auth");
        assertThat(covered.getStatus()).isEqualTo(SalePaymentSessionStatus.COVERED);
        covered.discardSimulation("sale_entry_cleanup",userId);
        assertThat(covered.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);

        var compensating=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),userId,"hash","{}",BigDecimal.TEN);
        compensating.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED")
                .result(PaymentTerminalOperationStatus.TIMEOUT,UUID.randomUUID(),null,null,"uncertain");
        compensating.cancel();
        assertThat(compensating.getStatus()).isEqualTo(SalePaymentSessionStatus.COMPENSATION_REQUIRED);
        compensating.discardSimulation("application_shutdown",userId);
        assertThat(compensating.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
    }

    @Test void finalizedSessionCannotBeDiscardedAsSimulation() {
        var userId=UUID.randomUUID();
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),userId,"hash","{}",BigDecimal.TEN);
        session.addAllocation(UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,BigDecimal.TEN,null,null).approve(null,null,null);
        session.finalizeWith(UUID.randomUUID(),"T-1");

        assertThatThrownBy(() -> session.discardSimulation("application_shutdown",userId))
                .hasMessage("payment_session_finalized");
        assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.FINALIZED);
    }

    @Test void simulatorDiscardRequiresReasonAndUser() {
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",BigDecimal.TEN);
        assertThatThrownBy(() -> session.discardSimulation(" ",UUID.randomUUID())).hasMessage("simulator_discard_reason_required");
        assertThatThrownBy(() -> session.discardSimulation("application_shutdown",null)).isInstanceOf(NullPointerException.class);
    }
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

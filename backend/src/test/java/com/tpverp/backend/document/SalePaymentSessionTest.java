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
    }

    @Test void finalizeIsIdempotentAndNeverReplacesTheFirstTicket() {
        var session=SalePaymentSession.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",new BigDecimal("1.00"));
        session.addAllocation(UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("1.00"),null,null).approve(null,null,null);
        var first=UUID.randomUUID();session.finalizeWith(first,"T-1");session.finalizeWith(UUID.randomUUID(),"T-2");
        assertThat(session.getTicketId()).isEqualTo(first);assertThat(session.getTicketNumber()).isEqualTo("T-1");
    }
}

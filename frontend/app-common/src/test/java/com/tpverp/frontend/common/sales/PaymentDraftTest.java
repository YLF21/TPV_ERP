package com.tpverp.frontend.common.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentDraftTest {

    @Test
    void calculatesRemainingAndDifferenceFromReceivedAmount() {
        PaymentDraft partial = new PaymentDraft(new BigDecimal("25.50"), new BigDecimal("20.00"));
        PaymentDraft overpaid = new PaymentDraft(new BigDecimal("25.50"), new BigDecimal("30.00"));

        assertEquals(new BigDecimal("5.50"), partial.remaining());
        assertEquals(new BigDecimal("-5.50"), partial.difference());
        assertEquals(new BigDecimal("0.00"), overpaid.remaining());
        assertEquals(new BigDecimal("4.50"), overpaid.difference());
    }
}

package com.tpverp.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusinessOperationClassifierTest {

    @Test
    void classifiesCriticalDomainsWithoutUsingResourceIdentifiersAsMetricTags() {
        assertOperation("POST", "/api/v1/tickets", "sale", "ticket.create", "TICKET_CREATED");
        assertOperation("POST", "/api/v1/tickets/11111111-1111-1111-1111-111111111111/returns",
                "return", "ticket.return", "TICKET_RETURN_CREATED");
        assertOperation("POST", "/api/v1/customer-receivables/id/payments",
                "credit", "receivable.payment", "CUSTOMER_RECEIVABLE_PAYMENT_RECORDED");
        assertOperation("POST", "/api/v1/pos/payment-sessions/id/finalize",
                "payment", "session.finalize", "PAYMENT_SESSION_FINALIZED");
        assertOperation("POST", "/api/v1/pos/cash",
                "sale", "cash.charge", "POS_CASH_SALE_COMPLETED");
        assertOperation("POST", "/api/v1/pos/card/charge",
                "payment", "card.charge", "POS_CARD_CHARGE_REQUESTED");
        assertOperation("PUT", "/api/v1/promotions/id",
                "promotion", "configuration.mutate", "PROMOTION_CONFIGURATION_CHANGED");
    }

    @Test
    void treatsQuotesAndReadsAsMeasuredButNotAuditedOperations() {
        var quote = BusinessOperationClassifier.classify(
                "POST", "/api/v1/pos/customer-pending-sales/quote?locale=es").orElseThrow();
        var promotion = BusinessOperationClassifier.classify(
                "POST", "/api/v1/promotions/preview").orElseThrow();
        var cashQuote = BusinessOperationClassifier.classify(
                "POST", "/api/v1/pos/cash/quote").orElseThrow();
        var creditAccount = BusinessOperationClassifier.classify(
                "GET", "/api/v1/customer-credit-accounts/id").orElseThrow();

        assertThat(quote).extracting(BusinessOperation::domain, BusinessOperation::name)
                .containsExactly("credit", "pending_sale.quote");
        assertThat(quote.isAudited()).isFalse();
        assertThat(promotion.name()).isEqualTo("pricing.evaluate");
        assertThat(promotion.isAudited()).isFalse();
        assertThat(cashQuote.name()).isEqualTo("cash.quote");
        assertThat(cashQuote.isAudited()).isFalse();
        assertThat(creditAccount.name()).isEqualTo("account.read");
        assertThat(creditAccount.isAudited()).isFalse();
    }

    @Test
    void ignoresUnrelatedApiTraffic() {
        assertThat(BusinessOperationClassifier.classify("GET", "/api/v1/products")).isEmpty();
        assertThat(BusinessOperationClassifier.classify("GET", "/actuator/health")).isEmpty();
    }

    private static void assertOperation(
            String method,
            String path,
            String domain,
            String name,
            String auditEvent) {
        var operation = BusinessOperationClassifier.classify(method, path).orElseThrow();
        assertThat(operation).isEqualTo(new BusinessOperation(domain, name, auditEvent));
    }
}

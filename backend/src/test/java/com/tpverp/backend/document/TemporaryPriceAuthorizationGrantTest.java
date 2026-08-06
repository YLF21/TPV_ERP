package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporaryPriceAuthorizationGrantTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-03T10:00:00Z");

    @Test
    void proofIsBoundToItsScopeAndCanOnlyBeConsumedOnce() {
        var scope = scope();
        var grant = grant(scope, ISSUED_AT.plusSeconds(1800));
        var firstCheckout = UUID.randomUUID();

        grant.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), scope.cartLineId(), scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", firstCheckout, ISSUED_AT.plusSeconds(10));
        grant.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), scope.cartLineId(), scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", firstCheckout, ISSUED_AT.plusSeconds(20));

        assertThatThrownBy(() -> grant.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), scope.cartLineId(), scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", UUID.randomUUID(), ISSUED_AT.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_price_authorization_in_use");

        grant.consume("PAYMENT_SESSION", firstCheckout, ISSUED_AT.plusSeconds(40));

        assertThatThrownBy(() -> grant.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), scope.cartLineId(), scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", firstCheckout, ISSUED_AT.plusSeconds(50)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_price_authorization_already_used");
    }

    @Test
    void releasedProofCanBeClaimedAgainUntilItExpires() {
        var scope = scope();
        var grant = grant(scope, ISSUED_AT.plusSeconds(1800));
        var firstCheckout = UUID.randomUUID();
        var secondCheckout = UUID.randomUUID();

        grant.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), scope.cartLineId(), scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", firstCheckout, ISSUED_AT.plusSeconds(10));
        grant.release("PAYMENT_SESSION", firstCheckout);
        grant.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), scope.cartLineId(), scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", secondCheckout, ISSUED_AT.plusSeconds(20));
    }

    @Test
    void expiredOrChangedScopeIsRejected() {
        var scope = scope();
        var expired = grant(scope, ISSUED_AT.plusSeconds(30));

        assertThatThrownBy(() -> expired.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), scope.cartLineId(), scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", UUID.randomUUID(), ISSUED_AT.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_price_authorization_expired");

        var mismatched = grant(scope, ISSUED_AT.plusSeconds(1800));
        assertThatThrownBy(() -> mismatched.claim(
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), "otra-linea", scope.productId(),
                scope.unitPrice(), scope.policyVersion(),
                "PAYMENT_SESSION", UUID.randomUUID(), ISSUED_AT.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary_price_authorization_mismatch");
    }

    private static TemporaryPriceAuthorizationGrant grant(
            Scope scope,
            Instant expiresAt) {
        return new TemporaryPriceAuthorizationGrant(
                "a".repeat(64),
                scope.companyId(), scope.storeId(), scope.terminalId(),
                scope.operatorId(), "operador",
                scope.authorizerId(), "responsable", true,
                scope.cartLineId(), scope.productId(), scope.unitPrice(),
                scope.policyVersion(), ISSUED_AT, expiresAt);
    }

    private static Scope scope() {
        return new Scope(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "linea-1",
                UUID.randomUUID(), new BigDecimal("8.50"), 7L);
    }

    private record Scope(
            UUID companyId,
            UUID storeId,
            UUID terminalId,
            UUID operatorId,
            UUID authorizerId,
            String cartLineId,
            UUID productId,
            BigDecimal unitPrice,
            long policyVersion) {
    }
}

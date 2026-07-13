package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalOperationTest {

    @Test
    void sanitizesSensitiveGatewayIdentifiersBeforeTheyEnterTheLedger() {
        var now=Instant.parse("2026-07-13T10:00:00Z");
        var operation=PaymentTerminalOperation.reserve(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                PaymentTerminalProvider.REDSYS_TPV_PC,PaymentTerminalMode.SIMULATED,PaymentTerminalOperationType.CHARGE,
                null,"safe-storage","a".repeat(64),BigDecimal.ONE,"b".repeat(64),1,now);
        operation.markSent("SEND",now.plusSeconds(1));
        operation.approve("CARD 4548 8120 4940 0004 secret=do-not-store","AUTH-123456 password:hunter2",
                now.plusSeconds(2));

        assertThat(operation.getExternalReference()).contains("****0004","[REDACTED]")
                .doesNotContain("4548 8120 4940 0004","do-not-store");
        assertThat(operation.getAuthorizationCode()).contains("AUTH-123456","[REDACTED]")
                .doesNotContain("hunter2");
    }

    private final Instant now = Instant.parse("2026-07-11T12:00:00Z");

    @Test
    void reservesChargeWithStableIdentityAndPendingEvent() {
        var operation = reserve();

        assertThat(operation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.PENDING);
        assertThat(operation.getCurrency()).isEqualTo("EUR");
        assertThat(operation.getRefundedAmount()).isEqualByComparingTo("0.00");
        assertThat(operation.getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getPreviousStatus()).isNull();
            assertThat(event.getNewStatus()).isEqualTo(PaymentTerminalOperationStatus.PENDING);
        });
    }

    @Test
    void permitsExplicitFinancialTransitionsAndAppendsImmutableHistory() {
        var operation = reserve();

        operation.markSent("attempt-1", now.plusSeconds(1));
        operation.approve("REF-1", "AUTH-1", now.plusSeconds(2));
        operation.recordRefund(new BigDecimal("4.00"), now.plusSeconds(3));
        operation.recordRefund(new BigDecimal("6.00"), now.plusSeconds(4));

        assertThat(operation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.REFUNDED);
        assertThat(operation.getRefundedAmount()).isEqualByComparingTo("10.00");
        assertThat(operation.getEvents()).extracting(PaymentTerminalEvent::getNewStatus)
                .containsExactly(PaymentTerminalOperationStatus.PENDING, PaymentTerminalOperationStatus.SENT,
                        PaymentTerminalOperationStatus.APPROVED,
                        PaymentTerminalOperationStatus.PARTIALLY_REFUNDED,
                        PaymentTerminalOperationStatus.REFUNDED);
        assertThatThrownBy(() -> operation.getEvents().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidTransitionAndRefundBeyondApprovedAmount() {
        var pending = reserve();
        assertThatThrownBy(() -> pending.approve("R", "A", now)).isInstanceOf(IllegalStateException.class);

        pending.markSent("attempt", now);
        pending.approve("R", "A", now);
        assertThatThrownBy(() -> pending.recordRefund(new BigDecimal("10.01"), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uncertainOperationCanMoveToManualReviewButCannotBeApprovedWithoutQuerying() {
        var operation = reserve();
        operation.markSent("attempt", now);
        operation.timeout("TIMEOUT", "Sin respuesta", now.plusSeconds(1));

        operation.markReviewRequired("QUERY_UNAVAILABLE", "Revision manual", now.plusSeconds(2));

        assertThat(operation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.REVIEW_REQUIRED);
        assertThatThrownBy(() -> operation.approve("R", "A", now.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void chargeCannotReferenceAnOriginalOperation() {
        assertThatThrownBy(() -> PaymentTerminalOperation.reserve(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), PaymentTerminalProvider.PAYTEF, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.CHARGE, UUID.randomUUID(), "sale-2", "a".repeat(64),
                BigDecimal.ONE, "b".repeat(64), 1, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryCanResolvePendingOrTimeoutWithoutSendingAnotherCharge() {
        var pending = reserve();
        pending.approveFromQuery("REF", "AUTH", now.plusSeconds(1));
        assertThat(pending.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);

        var timedOut = reserve();
        timedOut.markSent("ATTEMPT", now);
        timedOut.timeout("TIMEOUT", "Sin respuesta", now.plusSeconds(1));
        timedOut.declineFromQuery("DECLINED", "Rechazada", now.plusSeconds(2));
        assertThat(timedOut.getStatus()).isEqualTo(PaymentTerminalOperationStatus.DECLINED);
    }

    @Test
    void exposesTypedFailureCancellationLeaseRetryAndDocumentLinking() {
        var operation = reserve();
        var owner = UUID.randomUUID();
        assertThat(operation.claimProcessing(owner, now.plusSeconds(30), now)).isTrue();
        assertThat(operation.getProcessingOwner()).isEqualTo(owner);
        assertThat(operation.getProcessingLeaseUntil()).isEqualTo(now.plusSeconds(30));
        assertThat(operation.claimProcessing(UUID.randomUUID(), now.plusSeconds(30), now.plusSeconds(1))).isFalse();
        operation.scheduleRetry(now.plusSeconds(60), now.plusSeconds(2));
        assertThat(operation.getRetryCount()).isEqualTo(1);
        assertThat(operation.getNextRetryAt()).isEqualTo(now.plusSeconds(60));
        operation.markSent("ATTEMPT", now.plusSeconds(3));
        operation.fail("TRANSPORT_ERROR", "Fallo seguro", now.plusSeconds(4));
        assertThat(operation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.ERROR);

        var approved = reserve();
        approved.markSent("ATTEMPT", now);
        approved.approve("REF", "AUTH", now.plusSeconds(1));
        approved.linkDocument(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(5));
        assertThat(approved.getDocumentId()).isNotNull();
        assertThat(approved.getDocumentPaymentId()).isNotNull();

        var failed = reserve();
        failed.fail("TRANSPORT_ERROR", "Fallo seguro", now);
        assertThat(failed.getStatus()).isEqualTo(PaymentTerminalOperationStatus.ERROR);
    }

    @Test
    void localCancellationIsOnlyAllowedBeforeSendAndDocumentLinkRequiresApprovedCharge() {
        var pending = reserve();
        pending.cancelBeforeSend("USER_CANCELLED", now);
        assertThat(pending.getStatus()).isEqualTo(PaymentTerminalOperationStatus.CANCELLED);

        var sent = reserve();
        sent.markSent("ATTEMPT", now);
        assertThatThrownBy(() -> sent.cancelBeforeSend("USER_CANCELLED", now))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sent.linkDocument(UUID.randomUUID(), UUID.randomUUID(), now))
                .isInstanceOf(IllegalStateException.class);

        var voidOperation = PaymentTerminalOperation.reserve(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.VOID, UUID.randomUUID(), "void", "a".repeat(64),
                BigDecimal.ONE, "b".repeat(64), 1, now);
        voidOperation.markSent("ATTEMPT", now);
        voidOperation.voidApproved("REF", "AUTH", now.plusSeconds(1));
        assertThat(voidOperation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThatThrownBy(() -> sent.voidApproved("REF", "AUTH", now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voidRejectsGenericApprovalAndHasTypedDirectAndQueryApprovalRoutes() {
        var sentVoid = voidOperation();
        sentVoid.markSent("ATTEMPT", now);
        assertThatThrownBy(() -> sentVoid.approve("REF", "AUTH", now.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        sentVoid.voidApproved("REF", "AUTH", now.plusSeconds(1));
        assertThat(sentVoid.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);

        var pendingVoid = voidOperation();
        assertThatThrownBy(() -> pendingVoid.approveFromQuery("REF", "AUTH", now))
                .isInstanceOf(IllegalStateException.class);
        pendingVoid.voidApprovedFromQuery("REF", "AUTH", now.plusSeconds(1));
        assertThat(pendingVoid.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);

        var timedOutVoid = voidOperation();
        timedOutVoid.markSent("ATTEMPT", now);
        timedOutVoid.timeout("TIMEOUT", "Sin respuesta", now.plusSeconds(1));
        timedOutVoid.voidApprovedFromQuery("REF", "AUTH", now.plusSeconds(2));
        assertThat(timedOutVoid.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
    }

    @Test
    void eventsExposeSafeCodeDiagnosticAndImmutableMetadata() {
        var operation = reserve();
        operation.markSent("ATTEMPT_1", now.plusSeconds(1), Map.of("attempt", Map.of("number", 1)));
        var event = operation.getEvents().get(1);
        assertThat(event.getNormalizedCode()).isEqualTo("ATTEMPT_1");
        assertThat(event.getMetadata()).containsKey("attempt");
        assertThatThrownBy(() -> event.getMetadata().put("secret", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked") var nested = (Map<String, Object>) event.getMetadata().get("attempt");
        assertThatThrownBy(() -> nested.put("number", 2)).isInstanceOf(UnsupportedOperationException.class);
        var unsafe = reserve();
        unsafe.markSent("X", now, Map.of("apiToken", "secret"));
        assertThat(unsafe.getEvents().get(1).getMetadata()).doesNotContainKey("apiToken");
    }

    @Test
    void eventsSanitizeDiagnosticMetadataKeysAndStringValuesBeforeExposure() {
        var operation = reserve();
        operation.markSent("ATTEMPT", now,
                Map.of("pan", "4548 8120 4940 0004", "detail", "card=4111-1111-1111-1111"));
        operation.timeout("TIMEOUT", "PAN 5555 5555 5555 4444; auth 123456789012", now.plusSeconds(1));

        var sent = operation.getEvents().get(1);
        assertThat(sent.getMetadata()).doesNotContainKey("pan");
        assertThat(sent.getMetadata().get("detail").toString()).contains("****1111")
                .doesNotContain("4111-1111-1111-1111");
        assertThat(operation.getEvents().get(2).getDiagnostic()).contains("****4444", "****9012")
                .doesNotContain("5555 5555 5555 4444", "123456789012");
    }

    @Test
    void rejectsPrecisionAndOversizedExternalValuesInsteadOfRoundingOrTruncating() {
        assertThatThrownBy(() -> PaymentTerminalOperation.reserve(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), PaymentTerminalProvider.PAYCOMET, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.CHARGE, null, "sale", "a".repeat(64),
                new BigDecimal("1.001"), "b".repeat(64), 1, now))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PaymentTerminalOperation.reserve(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), PaymentTerminalProvider.PAYCOMET, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.CHARGE, null, "sale", "a".repeat(64),
                BigDecimal.ONE, null, 0, now)).isInstanceOf(IllegalArgumentException.class);

        var operation = reserve();
        operation.markSent("ATTEMPT", now);
        assertThatThrownBy(() -> operation.approve("R".repeat(129), "A", now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PaymentTerminalOperation reserve() {
        return PaymentTerminalOperation.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.CHARGE, null, "sale-1", "a".repeat(64),
                new BigDecimal("10.00"), "b".repeat(64), 1, now);
    }

    private PaymentTerminalOperation voidOperation() {
        return PaymentTerminalOperation.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.VOID, UUID.randomUUID(), "void-typed", "a".repeat(64),
                BigDecimal.ONE, "b".repeat(64), 1, now);
    }
}

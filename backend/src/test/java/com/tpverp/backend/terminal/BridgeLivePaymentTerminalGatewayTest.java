package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.terminal.bridge.BridgeHealth;
import com.tpverp.backend.terminal.bridge.BridgeOperationRequest;
import com.tpverp.backend.terminal.bridge.BridgeOperationResult;
import com.tpverp.backend.terminal.bridge.BridgePairingRequest;
import com.tpverp.backend.terminal.bridge.PaymentTerminalBridgeClient;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BridgeLivePaymentTerminalGatewayTest {
    static Stream<PaymentTerminalProvider> liveProviders() {
        return Stream.of(PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalProvider.PAYTEF,
                PaymentTerminalProvider.PAYCOMET, PaymentTerminalProvider.GLOBAL_PAYMENTS);
    }

    @ParameterizedTest
    @MethodSource("liveProviders")
    void exposesProviderSpecificLiveCapabilitiesAndConnectionHealth(PaymentTerminalProvider provider) {
        var bridge = new RecordingBridge();
        var gateway = new BridgeLivePaymentTerminalGateway(provider, bridge);

        assertThat(gateway.supports(provider, false)).isTrue();
        assertThat(gateway.supports(provider, true)).isFalse();
        assertThat(gateway.capabilities()).containsExactlyInAnyOrder(
                PaymentTerminalCapability.CONNECTION_TEST, PaymentTerminalCapability.PAIRING,
                PaymentTerminalCapability.CHARGE, PaymentTerminalCapability.QUERY,
                PaymentTerminalCapability.VOID, PaymentTerminalCapability.REFUND,
                PaymentTerminalCapability.RECEIPT, PaymentTerminalCapability.RECONCILIATION);
        assertThat(gateway.testConnection(configuration(provider)).status())
                .isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(bridge.lastCapabilitiesProvider).isEqualTo(provider.name());
    }

    @ParameterizedTest
    @MethodSource("liveProviders")
    void sendsChargeAndRecoveryDataWithoutCardData(PaymentTerminalProvider provider) {
        var bridge = new RecordingBridge();
        bridge.next = new BridgeOperationResult(true, "APPROVED", "provider-ref", "AUTH42", "Aprobada", null);
        var gateway = new BridgeLivePaymentTerminalGateway(provider, bridge);
        var operationId = UUID.randomUUID();

        var result = gateway.charge(new PaymentTerminalChargeCommand(operationId, new BigDecimal("12.34")), context(provider));

        assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(result.authorization()).isEqualTo("AUTH42");
        assertThat(bridge.lastOperation)
                .extracting(BridgeOperationRequest::provider, BridgeOperationRequest::terminalId,
                        BridgeOperationRequest::operationId, BridgeOperationRequest::command,
                        BridgeOperationRequest::amountMinor, BridgeOperationRequest::currency)
                .containsExactly(provider.name(), TERMINAL_ID.toString(), operationId.toString(), "CHARGE", 1234L, "EUR");
        assertThat(bridge.lastOperation.parameters()).containsExactlyEntriesOf(Map.of("ip", "127.0.0.1"));
    }

    @Test
    void routesPairQueryVoidRefundReceiptAndReconciliationThroughTheBridge() {
        var bridge = new RecordingBridge();
        var gateway = new BridgeLivePaymentTerminalGateway(PaymentTerminalProvider.REDSYS_TPV_PC, bridge);
        var context = context(PaymentTerminalProvider.REDSYS_TPV_PC);
        var original = UUID.randomUUID();
        var pairing = UUID.randomUUID();

        bridge.next = new BridgeOperationResult(true, "PAIRED", "pair-ref", null, "Emparejado", null);
        assertThat(gateway.pair(new PaymentTerminalPairCommand(pairing), context).code()).isEqualTo("PAIRED");
        assertThat(bridge.lastPairing.provider()).isEqualTo("REDSYS_TPV_PC");

        bridge.next = new BridgeOperationResult(false, "TIMEOUT", "charge-ref", null, "Pendiente", null);
        assertThat(gateway.query(new PaymentTerminalQueryCommand(original, "charge-ref"), context).status())
                .isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);
        assertThat(bridge.lastOperation.command()).isEqualTo("QUERY");

        bridge.next = new BridgeOperationResult(true, "VOIDED", "void-ref", null, "Anulada", null);
        assertThat(gateway.voidAuthorization(new PaymentTerminalVoidCommand(UUID.randomUUID(), original, "charge-ref"), context).status())
                .isEqualTo(PaymentTerminalOperationStatus.CANCELLED);
        assertThat(bridge.lastOperation.originalOperationId()).isEqualTo(original.toString());

        bridge.next = new BridgeOperationResult(true, "PARTIALLY_REFUNDED", "refund-ref", "R1", "Devuelta", null);
        assertThat(gateway.refund(new PaymentTerminalRefundCommand(UUID.randomUUID(), original,
                new BigDecimal("2.34"), "charge-ref"), context).status())
                .isEqualTo(PaymentTerminalOperationStatus.PARTIALLY_REFUNDED);
        assertThat(bridge.lastOperation.amountMinor()).isEqualTo(234L);

        bridge.next = new BridgeOperationResult(true, "RECEIPT_AVAILABLE", null, null, "OK",
                "COMERCIO\nPAN 4111111111111111\nAUTORIZACION 1234");
        assertThat(gateway.receipt(new PaymentTerminalReceiptCommand(original, "charge-ref"), context).text())
                .contains("****1111").doesNotContain("4111111111111111");

        bridge.next = new BridgeOperationResult(true, "RECONCILED", "batch-ref", null, "Conciliada", null);
        assertThat(gateway.reconcile(new PaymentTerminalReconciliationCommand(UUID.randomUUID()), context).status())
                .isEqualTo(PaymentTerminalOperationStatus.APPROVED);
        assertThat(bridge.lastOperation.command()).isEqualTo("RECONCILIATION");
    }

    @Test
    void unavailableOrMalformedBridgeNeverApprovesAndDoesNotInviteAnotherCharge() {
        var bridge = new RecordingBridge();
        var gateway = new BridgeLivePaymentTerminalGateway(PaymentTerminalProvider.GLOBAL_PAYMENTS, bridge);
        bridge.next = new BridgeOperationResult(false, "BRIDGE_UNAVAILABLE", null, null, null, null);
        var unavailable = gateway.charge(new PaymentTerminalChargeCommand(UUID.randomUUID(), BigDecimal.ONE),
                context(PaymentTerminalProvider.GLOBAL_PAYMENTS));
        assertThat(unavailable.status()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);
        assertThat(unavailable.finalOutcome()).isFalse();

        bridge.next = new BridgeOperationResult(true, "unexpected", "ref", null, null, null);
        var malformed = gateway.charge(new PaymentTerminalChargeCommand(UUID.randomUUID(), BigDecimal.ONE),
                context(PaymentTerminalProvider.GLOBAL_PAYMENTS));
        assertThat(malformed.status()).isEqualTo(PaymentTerminalOperationStatus.REVIEW_REQUIRED);
        assertThat(malformed.code()).isEqualTo("INVALID_RESPONSE");
    }

    private static final UUID TERMINAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static PaymentTerminalGatewayContext context(PaymentTerminalProvider provider) {
        return new PaymentTerminalGatewayContext(TERMINAL_ID, provider, PaymentTerminalMode.LIVE, "EUR", "stable-key",
                "terminal-payment:config", 4L, HASH, Map.of("ip", "127.0.0.1"));
    }

    private static CardTerminalConfiguration configuration(PaymentTerminalProvider provider) {
        return new CardTerminalConfiguration(TERMINAL_ID, UUID.randomUUID(), PaymentCardMode.INTEGRATED, provider,
                true, false, provider.name(), "terminal-payment:config", 4L, HASH, Map.of("ip", "127.0.0.1"));
    }

    private static final class RecordingBridge implements PaymentTerminalBridgeClient {
        private static final Set<String> ALL = Set.of("HEALTH", "PAIR", "CHARGE", "QUERY", "VOID", "REFUND", "RECEIPT", "RECONCILIATION");
        private String lastCapabilitiesProvider;
        private BridgePairingRequest lastPairing;
        private BridgeOperationRequest lastOperation;
        private BridgeOperationResult next = new BridgeOperationResult(true, "APPROVED", "reference", "AUTH", "OK", null);

        @Override public BridgeHealth health() { return new BridgeHealth(true, "OK", "1.0.0"); }
        @Override public Set<String> capabilities(String provider, String mode) {
            lastCapabilitiesProvider = provider;
            org.assertj.core.api.Assertions.assertThat(mode).isEqualTo("LIVE");
            return ALL;
        }
        @Override public BridgeOperationResult pair(BridgePairingRequest request) { lastPairing = request; return next; }
        @Override public BridgeOperationResult operate(BridgeOperationRequest request) { lastOperation = request; return next; }
    }
}

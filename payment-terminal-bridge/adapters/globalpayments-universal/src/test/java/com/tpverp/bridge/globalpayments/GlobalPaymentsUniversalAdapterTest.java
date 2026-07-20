package com.tpverp.bridge.globalpayments;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.AdapterRuntime;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.PaymentTerminalAdapter;
import com.tpverp.bridge.spi.TerminalProfile;
import com.tpverp.bridge.spi.TerminalExecutionMode;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GlobalPaymentsUniversalAdapterTest {
    private final GlobalPaymentsUniversalAdapter adapter = new GlobalPaymentsUniversalAdapter(
            java.util.List.of(new SimulatedGlobalPaymentsDriver()));

    @Test
    void isLoadedAsAPaymentTerminalPlugin() {
        assertThat(ServiceLoader.load(PaymentTerminalAdapter.class))
                .anyMatch(candidate -> GlobalPaymentsUniversalAdapter.ADAPTER_ID.equals(candidate.adapterId()));
    }

    @Test
    void supportsAnyModelWhenACompatibleProtocolDriverExists() {
        assertThat(adapter.supports(profile("UNASSIGNED-MODEL", Map.of("protocol", "SIMULATED")))).isTrue();
        assertThat(adapter.supports(profile("FUTURE-MODEL", Map.of()))).isTrue();
        assertThat(adapter.capabilities(profile("FUTURE-MODEL", Map.of())))
                .contains(BridgeCapability.CHARGE, BridgeCapability.QUERY, BridgeCapability.VOID,
                        BridgeCapability.REFUND, BridgeCapability.RECEIPT);
        assertThat(adapter.manifest().modes()).containsExactly(TerminalExecutionMode.SIMULATED);
        assertThat(adapter.manifest().certifiedLiveDriverInstalled()).isFalse();
    }

    @Test
    void failsClosedWhenNoCertifiedProtocolDriverMatches() {
        var live = new TerminalProfile("terminal-live", "GLOBAL_PAYMENTS",
                GlobalPaymentsUniversalAdapter.ADAPTER_ID, TerminalExecutionMode.LIVE, "UNKNOWN", "TCP_IP", "windows:secret",
                Map.of("protocol", "UPA"));

        assertThat(adapter.supports(live)).isFalse();
        assertThat(adapter.health(live).available()).isFalse();
        assertThat(adapter.health(live).code()).isEqualTo("DRIVER_NOT_INSTALLED");
    }

    @Test
    void simulatesPairChargeQueryAndReceiptWithoutMovingMoney() {
        var profile = profile("ANY", Map.of("protocol", "SIMULATED"));
        var pair = adapter.pair(new PairingRequest("GLOBAL_PAYMENTS", profile.terminalId(), TerminalExecutionMode.SIMULATED, "pair-1",
                "pair-key", null, 1, Map.of()), profile);
        var charge = adapter.operate(request("charge-1", "CHARGE", 514, null), profile);
        var query = adapter.operate(request("charge-1", "QUERY", 0, null), profile);
        var receipt = adapter.operate(request("charge-1", "RECEIPT", 0, null), profile);

        assertThat(pair.code()).isEqualTo("PAIRED");
        assertThat(charge.approved()).isTrue();
        assertThat(charge.code()).isEqualTo("APPROVED");
        assertThat(query).isEqualTo(charge);
        assertThat(receipt.code()).isEqualTo("RECEIPT_AVAILABLE");
        assertThat(receipt.receiptText()).contains("GLOBAL PAYMENTS - SIMULACION", "charge-1");
    }

    @Test
    void tracksPartialAndFullRefunds() {
        var profile = profile("ANY", Map.of());
        adapter.operate(request("charge-2", "CHARGE", 1_000, null), profile);

        var partial = adapter.operate(request("refund-1", "REFUND", 400, "charge-2"), profile);
        var full = adapter.operate(request("refund-2", "REFUND", 600, "charge-2"), profile);
        var overRefund = adapter.operate(request("refund-3", "REFUND", 1, "charge-2"), profile);

        assertThat(partial.code()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(full.code()).isEqualTo("REFUNDED");
        assertThat(overRefund.code()).isEqualTo("DECLINED");
    }

    @Test
    void exposesDeterministicNonApprovedTestOutcomes() {
        for (var code : Set.of("DECLINED", "CANCELLED", "PENDING", "TIMEOUT", "REVIEW_REQUIRED", "ERROR")) {
            var result = adapter.operate(request("charge-" + code, "CHARGE", 100, null),
                    profile("ANY", Map.of("simulationOutcome", code)));
            assertThat(result.approved()).isFalse();
            assertThat(result.code()).isEqualTo(code);
        }
    }

    @Test
    void restoresOperationsAfterDriverRestartFromTheBridgeStateStore() {
        var runtime = new MemoryRuntime();
        var first = new GlobalPaymentsUniversalAdapter(java.util.List.of(new SimulatedGlobalPaymentsDriver()));
        first.initialize(runtime);
        var profile = profile("ANY", Map.of());
        var charged = first.operate(request("durable-charge", "CHARGE", 500, null), profile);

        var restarted = new GlobalPaymentsUniversalAdapter(java.util.List.of(new SimulatedGlobalPaymentsDriver()));
        restarted.initialize(runtime);
        var recovered = restarted.operate(request("durable-charge", "QUERY", 0, null), profile);

        assertThat(recovered).isEqualTo(charged);
    }

    private static TerminalProfile profile(String model, Map<String, String> parameters) {
        return new TerminalProfile("terminal-sim", "GLOBAL_PAYMENTS",
                GlobalPaymentsUniversalAdapter.ADAPTER_ID, TerminalExecutionMode.SIMULATED, model, "SIMULATED", null, parameters);
    }

    private static OperationRequest request(String operationId, String command, long amountMinor, String original) {
        return new OperationRequest("GLOBAL_PAYMENTS", "terminal-sim", TerminalExecutionMode.SIMULATED, operationId, operationId + "-key",
                command, amountMinor, "EUR", original, null, null, 1, Map.of());
    }

    private static final class MemoryRuntime implements AdapterRuntime {
        private final java.util.Map<String, byte[]> state = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public <T> T withSecret(String reference, SecretUse<T> use) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<byte[]> readState(String namespace, String key) {
            var value = state.get(namespace + ':' + key);
            return value == null ? java.util.Optional.empty() : java.util.Optional.of(value.clone());
        }
        @Override public void writeState(String namespace, String key, byte[] value) {
            state.put(namespace + ':' + key, value.clone());
        }
        @Override public void deleteState(String namespace, String key) { state.remove(namespace + ':' + key); }
    }
}

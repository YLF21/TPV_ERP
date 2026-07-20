package com.tpverp.bridge.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.bridge.spi.AdapterHealth;
import com.tpverp.bridge.spi.AdapterManifest;
import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.PaymentTerminalAdapter;
import com.tpverp.bridge.spi.TerminalProfile;
import com.tpverp.bridge.spi.TerminalExecutionMode;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class BridgeServiceTest {
    @TempDir Path temporary;

    @Test
    void routesByConfiguredAdapterAndReturnsOnlyRealCapabilities() throws Exception {
        var adapter = new FakeAdapter("redsys-sdk", "REDSYS_TPV_PC", Set.of(BridgeCapability.CHARGE, BridgeCapability.QUERY));
        var service = service(adapter);

        assertThat(service.health()).extracting(BridgeHealthResult::available, BridgeHealthResult::code)
                .containsExactly(true, "OK");
        assertThat(service.capabilities("REDSYS_TPV_PC", TerminalExecutionMode.LIVE))
                .containsExactlyInAnyOrder(BridgeCapability.HEALTH, BridgeCapability.CHARGE, BridgeCapability.QUERY);
        assertThat(service.capabilities("GLOBAL_PAYMENTS", TerminalExecutionMode.LIVE)).isEmpty();
    }

    @Test
    void persistsIdempotencyAndNeverChargesTwice() throws Exception {
        var adapter = new FakeAdapter("redsys-sdk", "REDSYS_TPV_PC", Set.of(BridgeCapability.CHARGE));
        var service = service(adapter);
        var request = charge("same-key", 1234);

        var first = service.operate(request);
        var repeated = service.operate(request);

        assertThat(first).isEqualTo(repeated);
        assertThat(first.approved()).isTrue();
        assertThat(adapter.operations).hasValue(1);
    }

    @Test
    void rejectsReuseOfAnIdempotencyKeyForDifferentMoney() throws Exception {
        var adapter = new FakeAdapter("redsys-sdk", "REDSYS_TPV_PC", Set.of(BridgeCapability.CHARGE));
        var service = service(adapter);

        assertThat(service.operate(charge("same-key", 1234)).approved()).isTrue();
        var conflict = service.operate(charge("same-key", 9999));

        assertThat(conflict.code()).isEqualTo("REVIEW_REQUIRED");
        assertThat(adapter.operations).hasValue(1);
    }

    @Test
    void masksCardNumbersReturnedByAnUnsafeVendorAdapter() throws Exception {
        var adapter = new FakeAdapter("redsys-sdk", "REDSYS_TPV_PC", Set.of(BridgeCapability.CHARGE));
        adapter.result = new OperationResult(true, "APPROVED", "R-1", "A-1", "PAN 4111111111111111", null);

        var result = service(adapter).operate(charge("mask-key", 100));

        assertThat(result.message()).contains("****1111").doesNotContain("4111111111111111");
    }

    @Test
    void keepsProviderDisabledWhenItsPluginIsNotInstalled() throws Exception {
        var service = new BridgeService(new TerminalProfileRegistry(java.util.List.of(profile())), AdapterRegistry.of(),
                new FileIdempotencyStore(temporary, new ObjectMapper()));

        assertThat(service.health().available()).isFalse();
        assertThat(service.capabilities("REDSYS_TPV_PC", TerminalExecutionMode.LIVE)).isEmpty();
        assertThat(service.operate(charge("missing-key", 100)).code()).isEqualTo("SDK_NOT_INSTALLED");
    }

    @Test
    void exposesTheIntersectionForDifferentModelsOfTheSameProvider() throws Exception {
        var first = profile();
        var second = new TerminalProfile("terminal-2", "REDSYS_TPV_PC", "redsys-sdk", TerminalExecutionMode.LIVE, "MODEL-B", "TCP_IP",
                "windows:merchant-b", Map.of("ip", "127.0.0.2"));
        var adapter = new FakeAdapter("redsys-sdk", "REDSYS_TPV_PC", Set.of(BridgeCapability.CHARGE, BridgeCapability.REFUND)) {
            @Override public Set<BridgeCapability> capabilities(TerminalProfile profile) {
                return "MODEL-B".equals(profile.model()) ? Set.of(BridgeCapability.CHARGE) : super.capabilities(profile);
            }
        };
        var service = new BridgeService(new TerminalProfileRegistry(java.util.List.of(first, second)), AdapterRegistry.of(adapter),
                new FileIdempotencyStore(temporary, new ObjectMapper()));

        assertThat(service.capabilities("REDSYS_TPV_PC", TerminalExecutionMode.LIVE))
                .containsExactlyInAnyOrder(BridgeCapability.HEALTH, BridgeCapability.CHARGE);
    }

    @Test
    void neverRoutesALiveRequestToASimulatedProfile() throws Exception {
        var simulated = new TerminalProfile("terminal-1", "GLOBAL_PAYMENTS", "globalpayments-universal",
                TerminalExecutionMode.SIMULATED, "UNIVERSAL", "SIMULATED", null, Map.of("protocol", "SIMULATED"));
        var adapter = new FakeAdapter("globalpayments-universal", "GLOBAL_PAYMENTS", Set.of(BridgeCapability.CHARGE));
        var service = new BridgeService(new TerminalProfileRegistry(java.util.List.of(simulated)), AdapterRegistry.of(adapter),
                new FileIdempotencyStore(temporary, new ObjectMapper()));
        var liveRequest = new OperationRequest("GLOBAL_PAYMENTS", "terminal-1", TerminalExecutionMode.LIVE,
                "operation-live", "live-key", "CHARGE", 100, "EUR", null, null, "config-1", 1, Map.of());

        assertThat(service.capabilities("GLOBAL_PAYMENTS", TerminalExecutionMode.LIVE)).isEmpty();
        assertThat(service.operate(liveRequest).approved()).isFalse();
        assertThat(adapter.operations).hasValue(0);
    }

    private BridgeService service(PaymentTerminalAdapter adapter) throws Exception {
        return new BridgeService(new TerminalProfileRegistry(java.util.List.of(profile())), AdapterRegistry.of(adapter),
                new FileIdempotencyStore(temporary, new ObjectMapper()));
    }

    private static TerminalProfile profile() {
        return new TerminalProfile("terminal-1", "REDSYS_TPV_PC", "redsys-sdk", TerminalExecutionMode.LIVE, "MODEL-A", "TCP_IP",
                "windows:merchant-a", Map.of("ip", "127.0.0.1"));
    }

    private static OperationRequest charge(String key, long amount) {
        return new OperationRequest("REDSYS_TPV_PC", "terminal-1", TerminalExecutionMode.LIVE, "operation-1", key, "CHARGE", amount, "EUR",
                null, null, "config-1", 1L, Map.of());
    }

    private static class FakeAdapter implements PaymentTerminalAdapter {
        private final String adapterId;
        private final String provider;
        private final Set<BridgeCapability> capabilities;
        private final AtomicInteger operations = new AtomicInteger();
        private OperationResult result = new OperationResult(true, "APPROVED", "R-1", "A-1", "Aprobada", null);

        FakeAdapter(String adapterId, String provider, Set<BridgeCapability> capabilities) {
            this.adapterId = adapterId;
            this.provider = provider;
            this.capabilities = capabilities;
        }

        @Override public String adapterId() { return adapterId; }
        @Override public String provider() { return provider; }
        @Override public AdapterManifest manifest() {
            return new AdapterManifest(adapterId, provider, provider, Set.of(TerminalExecutionMode.LIVE),
                    Set.of("TEST"), Set.of("TCP_IP"), true);
        }
        @Override public boolean supports(TerminalProfile profile) { return true; }
        @Override public Set<BridgeCapability> capabilities(TerminalProfile profile) { return capabilities; }
        @Override public AdapterHealth health(TerminalProfile profile) { return new AdapterHealth(true, "OK", "test"); }
        @Override public OperationResult pair(PairingRequest request, TerminalProfile profile) {
            return new OperationResult(true, "PAIRED", "PAIR-1", null, "OK", null);
        }
        @Override public OperationResult operate(OperationRequest request, TerminalProfile profile) {
            operations.incrementAndGet();
            return result;
        }
    }
}

package com.tpverp.bridge.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.bridge.spi.AdapterHealth;
import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.PaymentTerminalAdapter;
import com.tpverp.bridge.spi.TerminalProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterRegistryTest {
    @TempDir Path temporary;

    @Test
    void refusesAnyPluginThatIsNotExplicitlyAllowListed() throws Exception {
        Files.createFile(temporary.resolve("unknown.jar"));
        assertThatThrownBy(() -> AdapterRegistry.load(temporary, Map.of()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void refusesDuplicateAdapterIdentifiers() {
        assertThatThrownBy(() -> AdapterRegistry.of(new Adapter("same"), new Adapter("same")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    private record Adapter(String adapterId) implements PaymentTerminalAdapter {
        @Override public String provider() { return "REDSYS_TPV_PC"; }
        @Override public boolean supports(TerminalProfile profile) { return true; }
        @Override public Set<BridgeCapability> capabilities(TerminalProfile profile) { return Set.of(); }
        @Override public AdapterHealth health(TerminalProfile profile) { return AdapterHealth.unavailable("TEST"); }
        @Override public OperationResult pair(PairingRequest request, TerminalProfile profile) { return OperationResult.failure("ERROR", "test"); }
        @Override public OperationResult operate(OperationRequest request, TerminalProfile profile) { return OperationResult.failure("ERROR", "test"); }
    }
}

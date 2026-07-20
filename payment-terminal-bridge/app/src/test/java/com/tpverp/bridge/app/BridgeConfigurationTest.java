package com.tpverp.bridge.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.bridge.spi.TerminalProfile;
import com.tpverp.bridge.spi.TerminalExecutionMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BridgeConfigurationTest {
    @Test
    void acceptsOnlyLoopbackAndUniqueTerminalProfiles() {
        var profile = new TerminalProfile("terminal-1", "GLOBAL_PAYMENTS", "globalpayments-sdk", TerminalExecutionMode.LIVE, "DX8000", "TCP_IP",
                "windows:gp-merchant", Map.of("ip", "127.0.0.1"));
        assertThatThrownBy(() -> new BridgeConfiguration(1, "192.168.1.10", 9123, "windows:token", "data", "plugins", Map.of(), List.of(profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loopback");
        assertThatThrownBy(() -> new BridgeConfiguration(1, "127.0.0.1", 9123, "windows:token", "data", "plugins", Map.of(), List.of(profile, profile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void terminalProfilesNeverAcceptSecretsAsPlainParameters() {
        assertThatThrownBy(() -> new TerminalProfile("terminal-1", "REDSYS_TPV_PC", "redsys-sdk", TerminalExecutionMode.LIVE, "M1", "USB",
                "windows:ref", Map.of("apiSecret", "plain-text")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Sensitive");

        new TerminalProfile("terminal-1", "REDSYS_TPV_PC", "redsys-sdk", TerminalExecutionMode.LIVE, "M1", "USB",
                "windows:ref", Map.of("pinpadHost", "127.0.0.1"));
    }
}

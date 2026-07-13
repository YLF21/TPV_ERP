package com.tpverp.backend.terminal.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LocalPaymentTerminalBridgeClientTest {
    @Test
    void rejectsRemoteTransportAndMissingAuthentication() {
        assertThatThrownBy(() -> LocalBridgeEndpoint.http(URI.create("http://192.168.1.10:9123"), "token"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("local");
        assertThatThrownBy(() -> LocalBridgeEndpoint.http(URI.create("http://127.0.0.1:9123"), " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authentication");
    }

    @Test
    void unavailableBridgeNeverApprovesFinancialOperation() {
        PaymentTerminalBridgeClient client = new UnavailablePaymentTerminalBridgeClient(
                LocalBridgeEndpoint.http(URI.create("http://localhost:9123"), "local-token"), Duration.ofSeconds(2));

        assertThat(client.health().available()).isFalse();
        assertThat(client.operate(new BridgeOperationRequest("op-1", "CHARGE", 1000, "EUR")).approved()).isFalse();
        assertThat(client.operate(new BridgeOperationRequest("op-1", "CHARGE", 1000, "EUR")).code())
                .isEqualTo("SDK_NOT_INSTALLED");
    }

    @Test
    void timeoutMustBePositiveAndBounded() {
        var endpoint = LocalBridgeEndpoint.http(URI.create("http://[::1]:9123"), "local-token");
        assertThatThrownBy(() -> new UnavailablePaymentTerminalBridgeClient(endpoint, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UnavailablePaymentTerminalBridgeClient(endpoint, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bridgeContractUsesAnExplicitCommandAllowListAndRedactsAuthentication() {
        var endpoint=LocalBridgeEndpoint.http(URI.create("http://localhost:9123"),"top-secret-token");
        assertThat(endpoint.toString()).doesNotContain("top-secret-token").contains("REDACTED");
        assertThatThrownBy(()->new BridgeOperationRequest("op-1","ARBITRARY_COMMAND",0,"EUR"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
    }
}

package com.tpverp.backend.terminal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackendDiscoveryAdvertisementTest {
    private final UUID installationId = UUID.randomUUID();

    @Test
    void advertisesTheConfiguredTlsOriginWithoutBusinessDataOrSecrets() {
        var value = BackendDiscoveryAdvertisement.create("https://servidor.tienda.example:8443/", installationId, "TIENDA-1");
        assertThat(value.port()).isEqualTo(8443);
        assertThat(value.properties()).containsOnlyKeys("url", "protocol", "installationId")
                .containsEntry("url", "https://servidor.tienda.example:8443")
                .containsEntry("installationId", installationId.toString());
        assertThat(value.name()).isEqualTo("esPOS-TIENDA-1");
        assertThat(BackendDiscoveryAdvertisement.create("https://servidor.example", installationId, "A").port()).isEqualTo(443);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.168.1.2:8080", "https://localhost", "https://127.0.0.2", "https://[::1]",
            "https://0.0.0.0", "https://[::]", "https://user:password@server.example", "https://server.example/api/v1",
            "https://server.example?secret=x", "https://server.example#fragment", "https://server.example:0", "https://server.example:99999", "invalid"})
    void refusesUnusableOrUnsafeLanAdvertisements(String value) {
        assertThatThrownBy(() -> BackendDiscoveryAdvertisement.create(value, installationId, "A"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsTxtRecordsAndRejectsIncompleteInstallationIdentity() {
        assertThatThrownBy(() -> BackendDiscoveryAdvertisement.create("https://" + "a".repeat(250) + ".example", installationId, "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackendDiscoveryAdvertisement.create("https://server.example", null, "A"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

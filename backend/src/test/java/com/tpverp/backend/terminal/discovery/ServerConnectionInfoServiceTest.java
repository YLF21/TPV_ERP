package com.tpverp.backend.terminal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerConnectionInfoServiceTest {
    @Test
    void readsSelectedPortsAndOnlyCurrentPrivateServerAddresses() throws Exception {
        var addresses = List.of(
                InetAddress.getByName("192.168.31.46"), InetAddress.getByName("192.168.82.2"),
                InetAddress.getByName("127.0.0.1"), InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("192.168.31.46"), InetAddress.getByName("::1"));
        var service = new ServerConnectionInfoService("https://tpv.tienda.local:18443", () -> addresses);
        var info = service.read(18080);
        assertThat(info.addresses()).containsExactly("192.168.31.46", "192.168.82.2");
        assertThat(info.httpsPort()).isEqualTo(18443);
        assertThat(info.backendPort()).isEqualTo(18080);
        assertThat(info.publicUrl()).isEqualTo("https://tpv.tienda.local:18443");
    }

    @Test
    void refreshesInterfacesInsteadOfKeepingAnOldIp() throws Exception {
        var address = new java.util.concurrent.atomic.AtomicReference<>(InetAddress.getByName("192.168.82.1"));
        var service = new ServerConnectionInfoService("https://10.0.2.2:8443", () -> List.of(address.get()));
        assertThat(service.read(8080).addresses()).containsExactly("192.168.82.1");
        address.set(InetAddress.getByName("192.168.82.2"));
        var info = service.read(8080);
        assertThat(info.addresses()).containsExactly("192.168.82.2");
        assertThat(info.publicUrl()).isEqualTo("https://10.0.2.2:8443");
    }

    @Test
    void doesNotInventTheHttpsPortIfTheGatewayHasNotBeenConfigured() {
        for (String value : new String[] {"", " ", "http://192.168.31.46:8443", "https://admin:password@tpv:8443",
                "https://tpv:8443/api", "https://tpv:8443?token=secret", "https://tpv:8443#secret", "https://tpv:0"}) {
            var info = new ServerConnectionInfoService(value, List::of).read(28080);
            assertThat(info.httpsPort()).isNull();
            assertThat(info.publicUrl()).isNull();
            assertThat(info.backendPort()).isEqualTo(28080);
        }
    }

    @Test
    void readsDefaultHttpsPortAndIpv6Authorities() {
        var info = new ServerConnectionInfoService("https://[2001:db8::1]/", List::of).read(8080);
        assertThat(info.httpsPort()).isEqualTo(443);
        assertThat(info.publicUrl()).isEqualTo("https://[2001:db8::1]");
    }

    @Test
    void retainsPortInformationWhenInterfacesCannotBeRead() {
        var info = new ServerConnectionInfoService("https://tpv:18443", () -> { throw new SocketException("fixture"); }).read(18080);
        assertThat(info.addresses()).isEmpty();
        assertThat(info.httpsPort()).isEqualTo(18443);
        assertThat(info.backendPort()).isEqualTo(18080);
    }

    @Test
    void rejectsAnUnknownBackendListenerInsteadOfDisplayingAConfiguredGuess() {
        var service = new ServerConnectionInfoService("https://tpv:8443", List::of);
        assertThatThrownBy(() -> service.read(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

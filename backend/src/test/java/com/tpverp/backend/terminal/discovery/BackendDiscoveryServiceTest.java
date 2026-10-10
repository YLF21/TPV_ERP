package com.tpverp.backend.terminal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackendDiscoveryServiceTest {
    private static final String URL = "https://servidor.tienda.example:8443";

    @Test
    void reconcilesChangedInterfacesWithoutChangingLogicalHttpsIdentity() throws Exception {
        var first = interfaceAddress("ethernet", "192.168.10.2");
        var second = interfaceAddress("wifi", "192.168.20.2");
        var addresses = new AtomicReference<>(List.of(first));
        var opened = new ArrayList<Published>();
        var installation = new Installation("TIENDA-1", "key", Instant.now());
        var repository = mock(InstallationRepository.class);
        when(repository.findAll()).thenReturn(List.of(installation));
        var service = new BackendDiscoveryService(repository, true, URL, "", ignored -> addresses.get(),
                (address, advertisement) -> {
                    var published = new Published(address, advertisement);
                    opened.add(published);
                    return published::close;
                });
        try {
            service.reconcile();
            service.reconcile();
            assertThat(opened).hasSize(1);
            assertThat(opened.getFirst().closed).isFalse();

            addresses.set(List.of(second));
            service.reconcile();
            assertThat(opened).hasSize(2);
            assertThat(opened.getFirst().closed).isTrue();
            assertThat(opened.get(1).closed).isFalse();
            assertThat(opened.get(1).address).isEqualTo(second.address());
            assertThat(opened.get(1).advertisement.port()).isEqualTo(8443);
            assertThat(opened.get(1).advertisement.properties()).containsOnlyKeys("url", "protocol", "installationId")
                    .containsEntry("url", URL)
                    .containsEntry("protocol", "1")
                    .containsEntry("installationId", installation.getId().toString());

            addresses.set(List.of());
            service.reconcile();
            assertThat(opened.get(1).closed).isTrue();
        } finally {
            service.close();
        }
    }

    @Test
    void retriesFailedPublicationAndClosesRemainingPublishersAtShutdown() throws Exception {
        var address = interfaceAddress("ethernet", "192.168.10.2");
        var installation = new Installation("TIENDA-1", "key", Instant.now());
        var repository = mock(InstallationRepository.class);
        when(repository.findAll()).thenReturn(List.of(installation));
        var attempts = new ArrayList<Published>();
        var service = new BackendDiscoveryService(repository, true, URL, "", ignored -> List.of(address),
                (bound, advertisement) -> {
                    if (attempts.isEmpty()) {
                        attempts.add(null);
                        throw new IOException("transient bind failure");
                    }
                    var published = new Published(bound, advertisement);
                    attempts.add(published);
                    return published::close;
                });
        service.reconcile();
        service.reconcile();
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(1).closed).isFalse();
        service.close();
        service.close();
        assertThat(attempts.get(1).closed).isTrue();
        service.reconcile();
        assertThat(attempts).hasSize(2);
    }

    @Test
    void keepsExistingAdvertisementWhenInterfaceEnumerationFails() throws Exception {
        var address = interfaceAddress("ethernet", "192.168.10.2");
        var addresses = new AtomicReference<>(List.of(address));
        var failEnumeration = new AtomicReference<>(false);
        var installation = new Installation("TIENDA-1", "key", Instant.now());
        var repository = mock(InstallationRepository.class);
        when(repository.findAll()).thenReturn(List.of(installation));
        var opened = new ArrayList<Published>();
        var service = new BackendDiscoveryService(repository, true, URL, "", ignored -> {
            if (failEnumeration.get()) throw new SocketException("interface unavailable");
            return addresses.get();
        }, (bound, advertisement) -> {
            var published = new Published(bound, advertisement);
            opened.add(published);
            return published::close;
        });
        try {
            service.reconcile();
            failEnumeration.set(true);
            org.assertj.core.api.Assertions.assertThatThrownBy(service::reconcile)
                    .isInstanceOf(SocketException.class);
            assertThat(opened.getFirst().closed).isFalse();
        } finally {
            service.close();
        }
    }

    private static BackendDiscoveryService.InterfaceAddress interfaceAddress(String name, String ip) throws Exception {
        return new BackendDiscoveryService.InterfaceAddress(name, InetAddress.getByName(ip));
    }

    private static final class Published {
        private final InetAddress address;
        private final BackendDiscoveryAdvertisement advertisement;
        private boolean closed;

        private Published(InetAddress address, BackendDiscoveryAdvertisement advertisement) {
            this.address = address;
            this.advertisement = advertisement;
        }

        private void close() {
            closed = true;
        }
    }
}

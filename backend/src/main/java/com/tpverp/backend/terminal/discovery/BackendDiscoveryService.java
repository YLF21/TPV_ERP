package com.tpverp.backend.terminal.discovery;

import com.tpverp.backend.installation.InstallationRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Publishes the configured HTTPS gateway on private LAN interfaces, without opening the API listener. */
@Component
public final class BackendDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(BackendDiscoveryService.class);
    private final InstallationRepository installations;
    private final boolean enabled;
    private final String publicUrl;
    private final String configuredInterfaces;
    private final List<JmDNS> publishers = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("tpv-backend-discovery").factory());
    private boolean started;
    private volatile boolean stopped;

    public BackendDiscoveryService(InstallationRepository installations,
            @Value("${tpv.terminal-discovery.enabled:${TPV_BACKEND_DISCOVERY_ENABLED:true}}") boolean enabled,
            @Value("${tpv.terminal-discovery.public-url:${TPV_BACKEND_PUBLIC_URL:}}") String publicUrl,
            @Value("${tpv.terminal-discovery.interfaces:${TPV_BACKEND_DISCOVERY_INTERFACES:}}") String configuredInterfaces) {
        this.installations = installations;
        this.enabled = enabled;
        this.publicUrl = publicUrl;
        this.configuredInterfaces = configuredInterfaces;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || stopped || !enabled || publicUrl == null || publicUrl.isBlank()) return;
        started = true;
        worker.submit(this::publish);
    }

    private void publish() {
        try {
            var installation = installations.findAll().stream().findFirst().orElseThrow();
            var advertisement = BackendDiscoveryAdvertisement.create(
                    publicUrl, installation.getId(), installation.getReferencia());
            for (var address : privateAddresses(configuredInterfaces)) {
                if (stopped) return;
                JmDNS publisher = null;
                try {
                    publisher = JmDNS.create(address);
                    publisher.registerService(ServiceInfo.create(BackendDiscoveryAdvertisement.SERVICE_TYPE,
                            advertisement.name(), advertisement.port(), 0, 0, advertisement.properties()));
                    synchronized (publishers) {
                        if (!stopped) {
                            publishers.add(publisher);
                            publisher = null;
                        }
                    }
                } catch (IOException | RuntimeException exception) {
                    log.warn("No se pudo anunciar el backend en {}: {}", address.getHostAddress(), exception.getClass().getSimpleName());
                } finally {
                    if (publisher != null) closePublisher(publisher);
                }
            }
        } catch (RuntimeException | SocketException exception) {
            // Discovery failure must not prevent local sales or manual connection.
            log.warn("Búsqueda automática del backend no disponible: {}", exception.getClass().getSimpleName());
        }
    }

    static List<InetAddress> privateAddresses(String configured) throws SocketException {
        Set<String> allowed = new LinkedHashSet<>();
        if (configured != null) Arrays.stream(configured.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).forEach(allowed::add);
        var result = new ArrayList<InetAddress>();
        var networkInterfaces = NetworkInterface.getNetworkInterfaces();
        if (networkInterfaces == null) return result;
        for (var networkInterface : Collections.list(networkInterfaces)) {
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) continue;
            for (var address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()
                        && (allowed.isEmpty() || allowed.contains(address.getHostAddress()))) result.add(address);
            }
        }
        return result;
    }

    @PreDestroy
    public void close() {
        stopped = true;
        worker.shutdownNow();
        List<JmDNS> closing;
        synchronized (publishers) {
            closing = new ArrayList<>(publishers);
            publishers.clear();
        }
        closing.forEach(BackendDiscoveryService::closePublisher);
    }

    private static void closePublisher(JmDNS publisher) {
        try { publisher.close(); }
        catch (IOException exception) { log.debug("No se pudo cerrar el anuncio DNS-SD", exception); }
    }
}

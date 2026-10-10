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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Publishes the configured HTTPS gateway on active private LAN interfaces. */
@Component
public final class BackendDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(BackendDiscoveryService.class);
    private static final long REFRESH_SECONDS = 30;
    private final InstallationRepository installations;
    private final boolean enabled;
    private final String publicUrl;
    private final String configuredInterfaces;
    private final AddressProvider addressProvider;
    private final PublisherFactory publisherFactory;
    private final Map<InterfaceAddress, Publisher> publishers = new LinkedHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("tpv-backend-discovery").factory());
    private boolean started;
    private volatile boolean stopped;

    @Autowired
    public BackendDiscoveryService(InstallationRepository installations,
            @Value("${tpv.terminal-discovery.enabled:${TPV_BACKEND_DISCOVERY_ENABLED:true}}") boolean enabled,
            @Value("${tpv.terminal-discovery.public-url:${TPV_BACKEND_PUBLIC_URL:}}") String publicUrl,
            @Value("${tpv.terminal-discovery.interfaces:${TPV_BACKEND_DISCOVERY_INTERFACES:}}") String configuredInterfaces) {
        this(installations, enabled, publicUrl, configuredInterfaces,
                BackendDiscoveryService::privateInterfaceAddresses, BackendDiscoveryService::openPublisher);
    }

    BackendDiscoveryService(InstallationRepository installations, boolean enabled, String publicUrl,
            String configuredInterfaces, AddressProvider addressProvider, PublisherFactory publisherFactory) {
        this.installations = installations;
        this.enabled = enabled;
        this.publicUrl = publicUrl;
        this.configuredInterfaces = configuredInterfaces;
        this.addressProvider = addressProvider;
        this.publisherFactory = publisherFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || stopped || !enabled || publicUrl == null || publicUrl.isBlank()) return;
        started = true;
        worker.scheduleWithFixedDelay(this::reconcileSafely, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    private void reconcileSafely() {
        try {
            reconcile();
        } catch (RuntimeException | SocketException exception) {
            // Discovery failure must not prevent local sales or manual connection; retry next cycle.
            log.warn("Búsqueda automática del backend no disponible: {}", exception.getClass().getSimpleName());
        }
    }

    synchronized void reconcile() throws SocketException {
        if (stopped || !enabled || publicUrl == null || publicUrl.isBlank()) return;
        var installation = installations.findAll().stream().findFirst().orElseThrow();
        var advertisement = BackendDiscoveryAdvertisement.create(
                publicUrl, installation.getId(), installation.getReferencia());
        var desired = new LinkedHashSet<>(addressProvider.activeAddresses(configuredInterfaces));

        List<Publisher> removed = new ArrayList<>();
        synchronized (publishers) {
            publishers.entrySet().removeIf(entry -> {
                if (desired.contains(entry.getKey())) return false;
                removed.add(entry.getValue());
                return true;
            });
        }
        removed.forEach(BackendDiscoveryService::closePublisher);

        for (var address : desired) {
            synchronized (publishers) {
                if (stopped) return;
                if (publishers.containsKey(address)) continue;
            }
            Publisher publisher = null;
            try {
                publisher = publisherFactory.open(address.address(), advertisement);
                synchronized (publishers) {
                    if (!stopped && !publishers.containsKey(address)) {
                        publishers.put(address, publisher);
                        publisher = null;
                    }
                }
            } catch (IOException | RuntimeException exception) {
                log.warn("No se pudo anunciar el backend en {}: {}", address.address().getHostAddress(),
                        exception.getClass().getSimpleName());
            } finally {
                if (publisher != null) closePublisher(publisher);
            }
        }
    }

    static List<InterfaceAddress> privateInterfaceAddresses(String configured) throws SocketException {
        Set<String> allowed = new LinkedHashSet<>();
        if (configured != null) Arrays.stream(configured.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).forEach(allowed::add);
        var result = new ArrayList<InterfaceAddress>();
        var networkInterfaces = NetworkInterface.getNetworkInterfaces();
        if (networkInterfaces == null) return result;
        for (var networkInterface : Collections.list(networkInterfaces)) {
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) continue;
            for (var address : Collections.list(networkInterface.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()
                        && (allowed.isEmpty() || allowed.contains(address.getHostAddress()))) {
                    result.add(new InterfaceAddress(networkInterface.getName(), address));
                }
            }
        }
        return result;
    }

    @PreDestroy
    public void close() {
        List<Publisher> closing;
        synchronized (publishers) {
            stopped = true;
            closing = new ArrayList<>(publishers.values());
            publishers.clear();
        }
        worker.shutdownNow();
        closing.forEach(BackendDiscoveryService::closePublisher);
    }

    private static Publisher openPublisher(InetAddress address, BackendDiscoveryAdvertisement advertisement)
            throws IOException {
        JmDNS jmDNS = JmDNS.create(address);
        try {
            jmDNS.registerService(ServiceInfo.create(BackendDiscoveryAdvertisement.SERVICE_TYPE,
                    advertisement.name(), advertisement.port(), 0, 0, advertisement.properties()));
            return jmDNS::close;
        } catch (IOException | RuntimeException exception) {
            try { jmDNS.close(); }
            catch (IOException closeException) { exception.addSuppressed(closeException); }
            throw exception;
        }
    }

    private static void closePublisher(Publisher publisher) {
        try { publisher.close(); }
        catch (IOException exception) { log.debug("No se pudo cerrar el anuncio DNS-SD", exception); }
    }

    record InterfaceAddress(String interfaceName, InetAddress address) { }

    @FunctionalInterface
    interface AddressProvider {
        List<InterfaceAddress> activeAddresses(String configured) throws SocketException;
    }

    @FunctionalInterface
    interface PublisherFactory {
        Publisher open(InetAddress address, BackendDiscoveryAdvertisement advertisement) throws IOException;
    }

    @FunctionalInterface
    interface Publisher {
        void close() throws IOException;
    }
}

package com.tpverp.backend.terminal.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-only connection details of this backend, never of the calling terminal. */
@Service
public class ServerConnectionInfoService {
    private static final Logger log = LoggerFactory.getLogger(ServerConnectionInfoService.class);
    private final String configuredPublicUrl;
    private final AddressProvider addresses;

    @Autowired
    public ServerConnectionInfoService(
            @Value("${tpv.terminal-discovery.public-url:${TPV_BACKEND_PUBLIC_URL:}}") String publicUrl) {
        this(publicUrl, () -> BackendDiscoveryService.privateInterfaceAddresses("").stream()
                .map(BackendDiscoveryService.InterfaceAddress::address).toList());
    }

    ServerConnectionInfoService(String publicUrl, AddressProvider addresses) {
        this.configuredPublicUrl = publicUrl;
        this.addresses = addresses;
    }

    public ConnectionInfo read(int backendPort) {
        if (backendPort < 1 || backendPort > 65535) {
            throw new IllegalArgumentException("El puerto de escucha del backend no es valido");
        }
        List<String> currentAddresses;
        try {
            currentAddresses = addresses.current().stream()
                    .filter(address -> address instanceof Inet4Address && address.isSiteLocalAddress()
                            && !address.isLoopbackAddress())
                    .map(InetAddress::getHostAddress).distinct().sorted().toList();
        } catch (SocketException exception) {
            log.warn("No se pudieron consultar las direcciones del servidor: {}", exception.getClass().getSimpleName());
            currentAddresses = List.of();
        }
        URI publicOrigin = publicOrigin();
        Integer httpsPort = publicOrigin == null ? null : (publicOrigin.getPort() < 0 ? 443 : publicOrigin.getPort());
        String publicUrl = publicOrigin == null ? null : publicOrigin.toASCIIString().replaceAll("/$", "");
        return new ConnectionInfo(currentAddresses, httpsPort, backendPort, publicUrl);
    }

    private URI publicOrigin() {
        if (configuredPublicUrl == null || configuredPublicUrl.isBlank()) return null;
        try {
            URI origin = URI.create(configuredPublicUrl.trim());
            if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty() && !"/".equals(origin.getRawPath()))
                    || origin.getPort() == 0 || origin.getPort() > 65535 || origin.getPort() < -1) return null;
            return origin;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record ConnectionInfo(List<String> addresses, Integer httpsPort, int backendPort, String publicUrl) { }

    @FunctionalInterface
    interface AddressProvider {
        List<InetAddress> current() throws SocketException;
    }
}

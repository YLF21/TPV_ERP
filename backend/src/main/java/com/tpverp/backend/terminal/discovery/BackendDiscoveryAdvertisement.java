package com.tpverp.backend.terminal.discovery;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Only connection metadata is advertised; a discovery answer never establishes trust. */
public record BackendDiscoveryAdvertisement(String name, int port, Map<String, String> properties) {
    public static final String SERVICE_TYPE = "_tpv-erp._tcp.local.";
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public static BackendDiscoveryAdvertisement create(String publicUrl, UUID installationId, String reference) {
        if (installationId == null || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("La instalación debe estar inicializada para anunciar el backend");
        }
        URI origin;
        try {
            origin = URI.create(publicUrl == null ? "" : publicUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("La dirección pública del backend no es válida", exception);
        }
        String host = origin.getHost();
        if (!"https".equalsIgnoreCase(origin.getScheme()) || host == null || host.isBlank()
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty() && !"/".equals(origin.getRawPath()))
                || LOOPBACK.contains(host.toLowerCase(java.util.Locale.ROOT)) || host.startsWith("127.")
                || "0.0.0.0".equals(host) || "[::]".equals(host)
                || origin.getPort() == 0 || origin.getPort() > 65535 || origin.getPort() < -1) {
            throw new IllegalArgumentException("El anuncio requiere el origen HTTPS accesible del backend, sin rutas ni credenciales");
        }
        String url = origin.toASCIIString().replaceAll("/$", "");
        // A DNS-SD TXT character-string is limited to 255 bytes, including the key.
        if (("url=" + url).getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("La dirección pública es demasiado larga para anunciarla");
        }
        String label = "esPOS-" + reference.replaceAll("[^a-zA-Z0-9_-]", "-");
        if (label.length() > 63) label = label.substring(0, 63);
        return new BackendDiscoveryAdvertisement(label, origin.getPort() < 0 ? 443 : origin.getPort(),
                Map.of("url", url, "protocol", "1", "installationId", installationId.toString()));
    }
}

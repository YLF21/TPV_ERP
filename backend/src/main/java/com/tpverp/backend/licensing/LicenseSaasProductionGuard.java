package com.tpverp.backend.licensing;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class LicenseSaasProductionGuard {

    private final String licenseUrl;
    private final String syncUrl;

    LicenseSaasProductionGuard(
            @Value("${tpv.license.saas-url}") String licenseUrl,
            @Value("${tpv.sync.central-url}") String syncUrl) {
        this.licenseUrl = licenseUrl;
        this.syncUrl = syncUrl;
    }

    @PostConstruct
    void validate() {
        URI licenseEndpoint = requireProductionEndpoint(licenseUrl, "TPV_LICENSE_SAAS_URL");
        URI syncEndpoint = requireProductionEndpoint(syncUrl, "TPV_SYNC_CENTRAL_URL");
        if (!sameOrigin(licenseEndpoint, syncEndpoint)) {
            throw new IllegalStateException(
                    "TPV_SYNC_CENTRAL_URL debe usar el mismo origen HTTPS que TPV_LICENSE_SAAS_URL");
        }
    }

    static URI requireProductionEndpoint(String configuredUrl) {
        return requireProductionEndpoint(configuredUrl, "TPV_LICENSE_SAAS_URL");
    }

    static URI requireProductionEndpoint(String configuredUrl, String variable) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalStateException(variable + " es obligatoria en produccion");
        }
        final URI endpoint;
        try {
            endpoint = URI.create(configuredUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(variable + " no es una URI valida", exception);
        }
        String host = endpoint.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        boolean placeholder = normalizedHost.equals("localhost")
                || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("::1")
                || normalizedHost.endsWith(".example")
                || normalizedHost.endsWith(".invalid");
        boolean rootPath = endpoint.getPath() == null
                || endpoint.getPath().isBlank()
                || endpoint.getPath().equals("/");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || normalizedHost.isBlank()
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !rootPath
                || placeholder) {
            throw new IllegalStateException(
                    variable + " debe ser la raiz HTTPS real del SaaS");
        }
        return endpoint;
    }

    private static boolean sameOrigin(URI first, URI second) {
        int firstPort = first.getPort() < 0 ? 443 : first.getPort();
        int secondPort = second.getPort() < 0 ? 443 : second.getPort();
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && firstPort == secondPort;
    }
}

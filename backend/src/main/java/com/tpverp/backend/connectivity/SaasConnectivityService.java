package com.tpverp.backend.connectivity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Reports current network reachability, independently of licensing and pending sync work. */
@Service
public class SaasConnectivityService {
    private static final Duration CACHE_DURATION = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI endpoint;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient client;
    private final Duration timeout;
    private ConnectivityStatus cached;

    @Autowired
    public SaasConnectivityService(
            @Value("${tpv.sync.central-url:${tpv.license.saas-url:}}") String baseUrl,
            ObjectMapper mapper, Clock clock) {
        this(baseUrl, mapper, clock, HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), REQUEST_TIMEOUT);
    }

    SaasConnectivityService(String baseUrl, ObjectMapper mapper, Clock clock,
            HttpClient client, Duration timeout) {
        this.endpoint = endpoint(baseUrl);
        this.mapper = mapper;
        this.clock = clock;
        this.client = client;
        this.timeout = timeout;
    }

    /** All terminals share one probe; concurrent polls do not create parallel SaaS requests. */
    public synchronized ConnectivityStatus status() {
        Instant now = clock.instant();
        if (cached != null && !now.isBefore(cached.checkedAt())
                && now.isBefore(cached.checkedAt().plus(CACHE_DURATION))) {
            return cached;
        }
        boolean connected = probe();
        cached = new ConnectivityStatus(connected, clock.instant());
        return cached;
    }

    private boolean probe() {
        if (endpoint == null) {
            return false;
        }
        try {
            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache, no-store")
                    .GET().build();
            var pending = client.sendAsync(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 4096));
            try {
                // The deadline includes the body, even when a peer stalls after sending headers.
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (response.statusCode() != 200) {
                    return false;
                }
                var body = mapper.readTree(response.body());
                return body != null && body.isObject()
                        && "tpv-erp-saas".equals(body.path("service").asText())
                        && "UP".equals(body.path("status").asText());
            } finally {
                pending.cancel(true);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            // A failed probe must never stop local sales or disclose remote response/configuration.
            return false;
        }
    }

    private static URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            var base = URI.create(baseUrl.strip().replaceAll("/+$", ""));
            if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                return null;
            }
            return URI.create(base + "/api/v1/connectivity/ping");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record ConnectivityStatus(boolean saasConnected,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant checkedAt) { }
}

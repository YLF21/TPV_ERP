package com.tpverp.backend.party;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Optional public address lookup: local party creation never depends on this service. */
@Service
public class PartyAddressService {
    private static final URI ENDPOINT = URI.create("https://www.cartociudad.es/geocoder/api/geocoder/");
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private final URI endpoint;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;

    @Autowired
    public PartyAddressService(ObjectMapper mapper) {
        this(ENDPOINT, mapper, PartyAddressHttpClientFactory.create(TIMEOUT), TIMEOUT);
    }

    // Package-private injection is for isolated HTTP tests; the application endpoint is fixed.
    PartyAddressService(URI endpoint, ObjectMapper mapper, HttpClient client, Duration timeout) {
        this.endpoint = endpoint;
        this.mapper = mapper;
        this.client = client;
        this.timeout = timeout;
    }

    public Suggestions suggestions(String q) {
        String query = q.strip();
        if (query.length() < 3) return new Suggestions(true, List.of());
        String parameters = "q=" + encode(query) + "&limit=12&countrycodes=es&no_process="
                + encode("municipio,provincia,comunidad autonoma,poblacion,toponimo,expendeduria,punto_recarga_electrica,ngbe,carretera");
        JsonNode body = request("candidates", parameters);
        if (body == null || !body.isArray()) return new Suggestions(false, List.of());
        List<Suggestion> items = new ArrayList<>();
        for (JsonNode item : body) {
            String type = text(item, "type");
            String id = text(item, "id");
            String label = text(item, "address");
            if (supportedType(type) && spanish(item) && !id.isBlank() && id.length() <= 200
                    && !label.isBlank()) {
                items.add(new Suggestion(id, type, label));
                if (items.size() == 12) break;
            }
        }
        return new Suggestions(true, List.copyOf(items));
    }

    public Resolved resolve(String id, String type) {
        if (!supportedType(type)) return new Resolved(false, null);
        JsonNode body = request("find", "id=" + encode(id) + "&type=" + encode(type));
        if (body == null || !body.isObject() || !spanish(body)
                || (body.hasNonNull("type") && !type.equals(text(body, "type")))) {
            return new Resolved(false, null);
        }
        String street = text(body, "address");
        if (street.isBlank()) return new Resolved(false, null);
        String kind = text(body, "tip_via");
        if (!kind.isBlank() && !street.equalsIgnoreCase(kind)
                && !street.regionMatches(true, 0, kind + " ", 0, kind.length() + 1)) {
            street = kind + " " + street;
        }
        if (!body.path("noNumber").asBoolean(false)) {
            String portal = text(body, "portalNumber");
            if (!portal.isBlank()) street += " " + portal + text(body, "extension");
        }
        String city = text(body, "muni");
        if (city.isBlank()) city = text(body, "poblacion");
        return new Resolved(true, new Address(street, text(body, "postalCode"), city.toUpperCase(Locale.ROOT),
                text(body, "province").toUpperCase(Locale.ROOT), "ES"));
    }

    private JsonNode request(String path, String parameters) {
        try {
            var request = HttpRequest.newBuilder(endpoint.resolve(path + "?" + parameters))
                    .timeout(timeout).header("Accept", "application/json").GET().build();
            var pending = client.sendAsync(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
            try {
                // Includes stalled bodies after response headers, unlike a connection-only timeout.
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return response.statusCode() == 200 ? mapper.readTree(response.body()) : null;
            } finally {
                pending.cancel(true);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            // Do not log queries or upstream payloads, which can contain personal addresses.
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean supportedType(String value) {
        return "portal".equals(value) || "callejero".equals(value);
    }

    private static boolean spanish(JsonNode node) {
        String country = text(node, "countryCode");
        return "011".equals(country) || "ES".equalsIgnoreCase(country);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isTextual() || value.isNumber() ? value.asText().strip() : "";
    }

    public record Suggestion(String id, String type, String label) { }
    public record Suggestions(boolean available, List<Suggestion> items) { }
    public record Address(String address, String postalCode, String city, String province, String country) { }
    public record Resolved(boolean available, Address address) { }
}

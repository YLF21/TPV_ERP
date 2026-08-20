package com.tpverp.backend.party.loyalty.central;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("tpv.sync.central-url")
public class HttpMemberBalanceCentralGateway implements MemberBalanceCentralGateway {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String INSTALLATION_TOKEN_HEADER = "X-TPV-Installation-Token";

    private final URI endpoint;
    private final URI pointsEndpoint;
    private final LicenseSaasCredentialStore credentials;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public HttpMemberBalanceCentralGateway(
            @Value("${tpv.sync.central-url}") URI centralUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper) {
        this(centralUrl, credentials, mapper, HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build());
    }

    HttpMemberBalanceCentralGateway(
            URI centralUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper,
            HttpClient client) {
        this.endpoint = URI.create(centralUrl.toString().replaceAll("/+$", "")
                + "/api/v2/loyalty/member-wallet");
        this.pointsEndpoint = URI.create(centralUrl.toString().replaceAll("/+$", "")
                + "/api/v2/loyalty/member-points");
        this.credentials = credentials;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public BootstrapResponse bootstrap(BootstrapRequest request) {
        return post("/bootstrap", request, BootstrapResponse.class);
    }

    @Override
    public Optional<MemberWalletBootstrapStatus> discoverBootstrap(
            BootstrapStoreRequest request) {
        return postOptional(
                "/bootstrap/discover", request, MemberWalletBootstrapStatus.class);
    }

    @Override
    public void beginBootstrapSnapshot(
            UUID bootstrapId,
            BootstrapSnapshotBeginRequest request) {
        postWithoutResponse("/bootstrap/" + bootstrapId + "/snapshots", request);
    }

    @Override
    public void uploadBootstrapChunk(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapChunkKind kind,
            int index,
            BootstrapSnapshotChunkRequest request) {
        request.validateFor(kind);
        postWithoutResponse(
                "/bootstrap/" + bootstrapId + "/snapshots/" + snapshotId
                        + "/chunks/" + kind.name() + "/" + index,
                request);
    }

    @Override
    public void completeBootstrapSnapshot(
            UUID bootstrapId,
            UUID snapshotId,
            BootstrapSnapshotCompleteRequest request) {
        postWithoutResponse(
                "/bootstrap/" + bootstrapId + "/snapshots/" + snapshotId + "/complete",
                request);
    }

    @Override
    public MemberWalletBootstrapStatus bootstrapStatus(
            UUID bootstrapId,
            BootstrapStoreRequest request) {
        return post(
                "/bootstrap/" + bootstrapId + "/status",
                request,
                MemberWalletBootstrapStatus.class);
    }

    @Override
    public ManualPointsAdjustmentResponse adjustPoints(
            ManualPointsAdjustmentRequest request) {
        return postPoints(
                "/adjustments", request, ManualPointsAdjustmentResponse.class);
    }

    @Override
    public OfficialPointsFeedResponse officialPointsFeed(
            OfficialPointsFeedRequest request) {
        return postPoints(
                "/official-feed", request, OfficialPointsFeedResponse.class);
    }

    @Override
    public Optional<PointsBootstrapStatus> discoverPointsBootstrap(
            PointsBootstrapStoreRequest request) {
        return postPointsOptional(
                "/bootstrap/discover", request, PointsBootstrapStatus.class);
    }

    @Override
    public PointsBootstrapStatus beginPointsBootstrapSnapshot(
            UUID bootstrapId,
            PointsBootstrapBeginRequest request) {
        return postPoints(
                "/bootstrap/" + bootstrapId + "/snapshots",
                request,
                PointsBootstrapStatus.class);
    }

    @Override
    public PointsBootstrapStatus uploadPointsBootstrapChunk(
            UUID bootstrapId,
            UUID snapshotId,
            PointsBootstrapChunkKind kind,
            int index,
            PointsBootstrapChunkRequest request) {
        return postPoints(
                "/bootstrap/" + bootstrapId + "/snapshots/" + snapshotId
                        + "/chunks/" + kind.name() + "/" + index,
                request,
                PointsBootstrapStatus.class);
    }

    @Override
    public PointsBootstrapStatus completePointsBootstrapSnapshot(
            UUID bootstrapId,
            UUID snapshotId,
            PointsBootstrapCompleteRequest request) {
        return postPoints(
                "/bootstrap/" + bootstrapId + "/snapshots/" + snapshotId
                        + "/complete",
                request,
                PointsBootstrapStatus.class);
    }

    @Override
    public PointsBootstrapStatus pointsBootstrapStatus(
            UUID bootstrapId,
            PointsBootstrapStoreRequest request) {
        return postPoints(
                "/bootstrap/" + bootstrapId + "/status",
                request,
                PointsBootstrapStatus.class);
    }

    @Override
    public PointsOfficialStateChunk pointsOfficialStateChunk(
            UUID bootstrapId,
            int index,
            PointsBootstrapStoreRequest request) {
        return postPoints(
                "/bootstrap/" + bootstrapId + "/official-state/chunks/" + index,
                request,
                PointsOfficialStateChunk.class);
    }

    @Override
    public ReservationResponse reserve(ReserveRequest request) {
        return post("/reservations", request, ReservationResponse.class);
    }

    @Override
    public ReservationResponse heartbeat(UUID reservationId, ReservationOwnerRequest request) {
        return post("/reservations/" + reservationId + "/heartbeat", request, ReservationResponse.class);
    }

    @Override
    public ReservationResponse release(UUID reservationId, ReservationOwnerRequest request) {
        return post("/reservations/" + reservationId + "/release", request, ReservationResponse.class);
    }

    @Override
    public ReservationResponse prepare(UUID reservationId, PrepareRequest request) {
        return post("/reservations/" + reservationId + "/prepare", request, ReservationResponse.class);
    }

    @Override
    public ReservationResponse finalizePrepared(UUID reservationId, PreparedOwnerRequest request) {
        return post("/reservations/" + reservationId + "/finalize", request, ReservationResponse.class);
    }

    @Override
    public ReservationResponse abortPrepared(UUID reservationId, PreparedOwnerRequest request) {
        return post("/reservations/" + reservationId + "/abort", request, ReservationResponse.class);
    }

    private <T> T post(String path, Object payload, Class<T> responseType) {
        return post(endpoint, path, payload, responseType);
    }

    private <T> T postPoints(String path, Object payload, Class<T> responseType) {
        return post(pointsEndpoint, path, payload, responseType);
    }

    private <T> Optional<T> postPointsOptional(
            String path,
            Object payload,
            Class<T> responseType) {
        return postOptional(pointsEndpoint, path, payload, responseType);
    }

    private <T> T post(
            URI target,
            String path,
            Object payload,
            Class<T> responseType) {
        HttpResponse<String> response = send(target, path, payload);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            if (response.body() == null || response.body().isBlank()) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.INVALID_RESPONSE,
                        "SaaS no devolvio el cuerpo esperado");
            }
            try {
                return mapper.readValue(response.body(), responseType);
            } catch (Exception exception) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.INVALID_RESPONSE,
                        "SaaS devolvio una respuesta de saldo socio invalida",
                        exception);
            }
        }
        throw responseException(response);
    }

    private <T> Optional<T> postOptional(
            String path,
            Object payload,
            Class<T> responseType) {
        return postOptional(endpoint, path, payload, responseType);
    }

    private <T> Optional<T> postOptional(
            URI target,
            String path,
            Object payload,
            Class<T> responseType) {
        HttpResponse<String> response = send(target, path, payload);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            if (response.statusCode() == 204
                    || response.body() == null
                    || response.body().isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(mapper.readValue(response.body(), responseType));
            } catch (Exception exception) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.INVALID_RESPONSE,
                        "SaaS devolvio un descubrimiento de bootstrap invalido",
                        exception);
            }
        }
        throw responseException(response);
    }

    private void postWithoutResponse(String path, Object payload) {
        HttpResponse<String> response = send(path, payload);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw responseException(response);
        }
    }

    private HttpResponse<String> send(String path, Object payload) {
        return send(endpoint, path, payload);
    }

    private HttpResponse<String> send(URI target, String path, Object payload) {
        String token = credentials.readToken().orElseThrow(() -> new MemberBalanceCentralException(
                MemberBalanceCentralException.Kind.UNAVAILABLE,
                "No existe token de instalacion SaaS"));
        try {
            HttpRequest request = HttpRequest.newBuilder(target.resolve(target.getPath() + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(INSTALLATION_TOKEN_HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.UNAVAILABLE,
                    "Operacion SaaS interrumpida",
                    exception);
        } catch (MemberBalanceCentralException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.UNAVAILABLE,
                    "No se pudo contactar con el servicio central de saldo socio",
                    exception);
        }
    }

    private MemberBalanceCentralException responseException(HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = responseDetail(response.body());
        MemberBalanceCentralException.Kind kind = switch (status) {
            case 401, 403 -> MemberBalanceCentralException.Kind.UNAUTHORIZED;
            case 409 -> MemberBalanceCentralException.Kind.CONFLICT;
            case 400, 404, 422 -> MemberBalanceCentralException.Kind.REJECTED;
            default -> status >= 500
                    ? MemberBalanceCentralException.Kind.UNAVAILABLE
                    : MemberBalanceCentralException.Kind.INVALID_RESPONSE;
        };
        return new MemberBalanceCentralException(
                kind,
                status,
                "SaaS respondio " + status + (detail == null ? "" : ": " + detail));
    }

    private String responseDetail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = mapper.readTree(body);
            if (error.hasNonNull("detail")) {
                return error.get("detail").asText();
            }
            if (error.hasNonNull("message")) {
                return error.get("message").asText();
            }
        } catch (Exception ignored) {
            // An unknown remote body must not leak HTML or infrastructure details to APP VENTA.
        }
        return null;
    }
}

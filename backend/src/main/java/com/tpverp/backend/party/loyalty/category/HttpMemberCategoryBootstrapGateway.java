package com.tpverp.backend.party.loyalty.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("tpv.sync.central-url")
public class HttpMemberCategoryBootstrapGateway implements MemberCategoryBootstrapGateway {
    private static final String TOKEN_HEADER = "X-TPV-Installation-Token";
    private final URI endpoint;
    private final URI officialSnapshotEndpoint;
    private final URI officialFeedEndpoint;
    private final URI adminCategoryEndpoint;
    private final URI adminAssignmentEndpoint;
    private final LicenseSaasCredentialStore credentials;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpMemberCategoryBootstrapGateway(
            @org.springframework.beans.factory.annotation.Value("${tpv.sync.central-url}") URI centralUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper) {
        this.endpoint = URI.create(centralUrl.toString().replaceAll("/+$", "")
                + "/api/v2/loyalty/member-categories/bootstrap");
        this.officialSnapshotEndpoint = URI.create(centralUrl.toString().replaceAll("/+$", "")
                + "/api/v2/loyalty/member-categories/official/snapshot");
        this.officialFeedEndpoint = URI.create(centralUrl.toString().replaceAll("/+$", "")
                + "/api/v2/loyalty/member-categories/official/feed");
        this.adminCategoryEndpoint = URI.create(centralUrl.toString().replaceAll("/+$", "")
                + "/api/v2/loyalty/member-categories/admin/categories");
        this.adminAssignmentEndpoint = URI.create(centralUrl.toString().replaceAll("/+$", "")
                + "/api/v2/loyalty/member-categories/admin/assignments");
        this.credentials = credentials;
        this.mapper = mapper;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public BootstrapStatus discover(UUID companyId, UUID storeId) {
        return post("/discover", store(companyId, storeId));
    }

    @Override
    public BootstrapStatus status(
            UUID bootstrapId,
            UUID companyId,
            UUID storeId) {
        return post("/" + bootstrapId + "/status", store(companyId, storeId));
    }

    @Override
    public BootstrapStatus begin(
            UUID bootstrapId,
            UUID companyId,
            UUID storeId,
            MemberCategoryBootstrapSnapshot snapshot,
            int categoryChunkCount,
            int assignmentChunkCount) {
        var body = new LinkedHashMap<String, Object>();
        body.put("companyId", companyId);
        body.put("storeId", storeId);
        body.put("snapshotId", snapshot.getSnapshotId());
        body.put("categoryChunkCount", categoryChunkCount);
        body.put("assignmentChunkCount", assignmentChunkCount);
        body.put("categoryCount", snapshot.getCategoryCount());
        body.put("assignmentCount", snapshot.getAssignmentCount());
        body.put("categoryHash", snapshot.getCategoryHash());
        body.put("assignmentHash", snapshot.getAssignmentHash());
        body.put("snapshotChecksum", snapshot.getSnapshotChecksum());
        return post("/" + bootstrapId + "/snapshots", body);
    }

    @Override
    public BootstrapStatus uploadCategories(
            UUID bootstrapId,
            UUID snapshotId,
            UUID companyId,
            UUID storeId,
            int index,
            String chunkHash,
            List<CategoryValue> values) {
        return post(
                chunkPath(bootstrapId, snapshotId, "CATEGORIES", index),
                chunk(companyId, storeId, chunkHash, values, List.of()));
    }

    @Override
    public BootstrapStatus uploadAssignments(
            UUID bootstrapId,
            UUID snapshotId,
            UUID companyId,
            UUID storeId,
            int index,
            String chunkHash,
            List<AssignmentValue> values) {
        return post(
                chunkPath(bootstrapId, snapshotId, "ASSIGNMENTS", index),
                chunk(companyId, storeId, chunkHash, List.of(), values));
    }

    @Override
    public BootstrapStatus complete(
            UUID bootstrapId,
            UUID snapshotId,
            UUID companyId,
            UUID storeId,
            String snapshotChecksum) {
        return post(
                "/" + bootstrapId + "/snapshots/" + snapshotId + "/complete",
                Map.of(
                        "companyId", companyId,
                        "storeId", storeId,
                        "snapshotChecksum", snapshotChecksum));
    }

    @Override
    public OfficialSnapshot officialSnapshot(UUID companyId, UUID storeId) {
        return post(
                officialSnapshotEndpoint,
                store(companyId, storeId),
                OfficialSnapshot.class);
    }

    @Override
    public OfficialFeed officialFeed(
            UUID companyId,
            UUID storeId,
            long afterConfigRevision,
            UUID afterConfigId,
            long afterAssignmentRevision,
            UUID afterAssignmentId,
            int limit) {
        var body = new LinkedHashMap<String, Object>();
        body.put("companyId", companyId);
        body.put("storeId", storeId);
        body.put("afterConfigRevision", afterConfigRevision);
        body.put("afterConfigId", afterConfigId);
        body.put("afterAssignmentRevision", afterAssignmentRevision);
        body.put("afterAssignmentId", afterAssignmentId);
        body.put("limit", limit);
        return post(officialFeedEndpoint, body, OfficialFeed.class);
    }

    @Override
    public AdminResult adminCategory(AdminCategoryCommand command) {
        return post(adminCategoryEndpoint, command, AdminResult.class);
    }

    @Override
    public AdminResult adminAssignment(AdminAssignmentCommand command) {
        return post(adminAssignmentEndpoint, command, AdminResult.class);
    }

    private BootstrapStatus post(String path, Object body) {
        return post(
                endpoint.resolve(endpoint.getPath() + path),
                body,
                BootstrapStatus.class);
    }

    private <T> T post(URI target, Object body, Class<T> responseType) {
        try {
            String token = credentials.readToken()
                    .orElseThrow(() -> new IllegalStateException(
                            "No existe token de instalacion SaaS"));
            var request = HttpRequest.newBuilder(target)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(TOKEN_HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "SaaS respondio " + response.statusCode() + " al sincronizar categorias");
            }
            return mapper.readValue(response.body(), responseType);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sincronizacion de categorias interrumpida", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException(
                    "No se pudo sincronizar el bootstrap de categorias", exception);
        }
    }

    private static Map<String, Object> store(UUID companyId, UUID storeId) {
        return Map.of("companyId", companyId, "storeId", storeId);
    }

    private static Map<String, Object> chunk(
            UUID companyId,
            UUID storeId,
            String chunkHash,
            List<CategoryValue> categories,
            List<AssignmentValue> assignments) {
        var body = new LinkedHashMap<String, Object>();
        body.put("companyId", companyId);
        body.put("storeId", storeId);
        body.put("chunkHash", chunkHash);
        body.put("categories", categories);
        body.put("assignments", assignments);
        return body;
    }

    private static String chunkPath(
            UUID bootstrapId,
            UUID snapshotId,
            String kind,
            int index) {
        return "/" + bootstrapId + "/snapshots/" + snapshotId
                + "/chunks/" + kind + "/" + index;
    }
}

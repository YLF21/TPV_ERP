package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record IntegrationEndpointResponse(
        UUID id,
        UUID companyId,
        String companyName,
        String name,
        String integrationType,
        String status,
        String targetUrl,
        String apiKeyPreview,
        Instant lastSyncAt,
        Instant createdAt) {
}

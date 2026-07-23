package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record CreateIntegrationRequest(
        UUID companyId,
        @NotBlank String name,
        @NotBlank String integrationType,
        String status,
        String targetUrl,
        String apiKey) {
}

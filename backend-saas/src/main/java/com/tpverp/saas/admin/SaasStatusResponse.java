package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.List;

public record SaasStatusResponse(
        Instant generatedAt,
        String apiVersion,
        String expectedMigration,
        List<String> modules) {
}

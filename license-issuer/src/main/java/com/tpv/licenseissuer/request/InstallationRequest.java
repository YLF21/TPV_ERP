package com.tpv.licenseissuer.request;

import java.security.PublicKey;

public record InstallationRequest(String id, String reference, PublicKey publicKey) {
    public InstallationRequest {
        id = requireText(id, "id");
        reference = requireText(reference, "reference");
        if (publicKey == null || !"RSA".equalsIgnoreCase(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("an RSA public key is required");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}

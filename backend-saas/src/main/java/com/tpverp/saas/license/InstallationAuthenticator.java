package com.tpverp.saas.license;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InstallationAuthenticator {

    private final TokenHasher tokens;

    public InstallationAuthenticator(TokenHasher tokens) {
        this.tokens = tokens;
    }

    public void requireToken(SaasInstallation installation, String token) {
        if (!installation.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de instalacion invalido");
        }
        requireKnownToken(installation, token);
    }

    /**
     * Verifies ownership of an already linked installation even after it has
     * been revoked. This is intentionally only used by license validation so
     * the local ERP can receive the blocking status instead of treating a
     * revocation as a transient outage.
     */
    public void requireKnownToken(SaasInstallation installation, String token) {
        if (token == null || token.isBlank()
                || !installation.hasTokenHash(tokens.hash(token))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de instalacion invalido");
        }
    }

    public SaasInstallation requireLinkedInstallation(
            UUID companyId,
            UUID storeId,
            List<SaasInstallation> candidates,
            String token) {
        if (companyId == null || storeId == null || token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Instalacion no autorizada");
        }
        String tokenHash = tokens.hash(token);
        return candidates.stream()
                .filter(SaasInstallation::isActive)
                .filter(candidate -> candidate.getCompany().getId().equals(companyId))
                .filter(candidate -> candidate.getStore().getId().equals(storeId))
                .filter(candidate -> candidate.hasTokenHash(tokenHash))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Ninguna instalacion vinculada acepta el token indicado"));
    }
}

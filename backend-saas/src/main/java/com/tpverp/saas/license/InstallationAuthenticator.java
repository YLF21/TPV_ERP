package com.tpverp.saas.license;

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
        if (token == null || token.isBlank() || !installation.hasTokenHash(tokens.hash(token))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token de instalacion invalido");
        }
    }
}

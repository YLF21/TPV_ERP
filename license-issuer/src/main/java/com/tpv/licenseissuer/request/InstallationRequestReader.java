package com.tpv.licenseissuer.request;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class InstallationRequestReader {
    private final Gson gson = new Gson();

    public InstallationRequest read(String json) {
        try {
            RawRequest raw = gson.fromJson(json, RawRequest.class);
            if (raw == null) {
                throw new IllegalArgumentException("installation request is required");
            }
            return new InstallationRequest(raw.id(), raw.reference(), parsePublicKey(raw.publicKey()));
        } catch (JsonParseException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid installation request", exception);
        }
    }

    private PublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        if (pem == null) {
            throw new IllegalArgumentException("publicKey is required");
        }
        String encoded = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(encoded);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private record RawRequest(String id, String reference, String publicKey) {
    }
}

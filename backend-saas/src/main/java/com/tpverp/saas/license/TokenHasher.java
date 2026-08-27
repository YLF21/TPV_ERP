package com.tpverp.saas.license;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TokenHasher {

    private final SecureRandom random = new SecureRandom();

    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular hash de token", exception);
        }
    }

    /**
     * Derives the stable credential returned by every recovery of the same
     * consumed pairing attempt. The caller credential already has at least
     * 256 bits of entropy; domain and identifiers prevent reuse across links.
     */
    public String deriveRecoveredToken(String credential, UUID pairingId, UUID installationId) {
        if (credential == null || credential.isBlank()
                || pairingId == null || installationId == null) {
            throw new IllegalArgumentException("Datos de recuperacion incompletos");
        }
        return hash("TPV-SAAS-LINK-RECOVERY-v1\n"
                + pairingId + "\n" + installationId + "\n" + credential);
    }
}

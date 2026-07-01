package com.tpverp.saas.license;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
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
}

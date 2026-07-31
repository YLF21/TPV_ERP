package com.tpverp.saas.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminPasswordHasher {

    private static final int BCRYPT_STRENGTH = 12;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String encodedPassword) {
        if (encodedPassword == null || password == null) {
            return false;
        }
        if (encodedPassword.startsWith("$2a$")
                || encodedPassword.startsWith("$2b$")
                || encodedPassword.startsWith("$2y$")) {
            return encoder.matches(password, encodedPassword);
        }
        if (!encodedPassword.matches("(?i)^[0-9a-f]{64}$")) {
            return false;
        }
        return MessageDigest.isEqual(
                legacySha256(password).getBytes(StandardCharsets.US_ASCII),
                encodedPassword.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public boolean needsUpgrade(String encodedPassword) {
        return encodedPassword == null || !encodedPassword.startsWith("$2");
    }

    private String legacySha256(String password) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular hash de password admin", exception);
        }
    }
}

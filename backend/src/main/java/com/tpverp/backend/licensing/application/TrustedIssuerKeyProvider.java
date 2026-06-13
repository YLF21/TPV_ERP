package com.tpverp.backend.licensing.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class TrustedIssuerKeyProvider {

    private final Path publicKeyFile;

    public TrustedIssuerKeyProvider(Path publicKeyFile) {
        this.publicKeyFile = publicKeyFile;
    }

    public PublicKey load() {
        try {
            if (publicKeyFile == null || !Files.isRegularFile(publicKeyFile)) {
                throw new LicenseValidationException(
                        "No se ha configurado la clave publica del proveedor de licencias");
            }
            String pem = Files.readString(publicKeyFile);
            String encoded = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (LicenseValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LicenseValidationException(
                    "No se pudo leer la clave publica del proveedor", exception);
        }
    }
}

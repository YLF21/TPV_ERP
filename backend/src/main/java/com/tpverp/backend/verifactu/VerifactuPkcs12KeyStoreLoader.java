package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import org.springframework.stereotype.Component;

@Component
public class VerifactuPkcs12KeyStoreLoader {

    public KeyStore load(Path path, char[] password) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("archivo de certificado PKCS#12 no encontrado");
        }
        try (var input = Files.newInputStream(path)) {
            var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, password == null ? new char[0] : password.clone());
            return keyStore;
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("No se pudo cargar el certificado PKCS#12", exception);
        }
    }
    // Carga el almacen PKCS#12 sin exponer la password ni convertirla a String.
}

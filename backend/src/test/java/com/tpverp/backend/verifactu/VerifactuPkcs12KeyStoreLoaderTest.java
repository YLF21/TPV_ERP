package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifactuPkcs12KeyStoreLoaderTest {

    @TempDir Path tempDir;

    @Test
    void cargaAlmacenPkcs12DesdeDisco() throws Exception {
        var password = "secreto".toCharArray();
        var file = tempDir.resolve("aeat.p12");
        createEmptyPkcs12(file, password);

        var keyStore = new VerifactuPkcs12KeyStoreLoader().load(file, password);

        assertThat(keyStore.getType()).isEqualTo("PKCS12");
        assertThat(keyStore.size()).isZero();
    }

    @Test
    void rechazaRutaInexistenteOPasswordIncorrecto() throws Exception {
        var file = tempDir.resolve("aeat.p12");
        createEmptyPkcs12(file, "correcta".toCharArray());
        var loader = new VerifactuPkcs12KeyStoreLoader();

        assertThatThrownBy(() -> loader.load(tempDir.resolve("no-existe.p12"), "x".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("certificado");
        assertThatThrownBy(() -> loader.load(file, "mala".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PKCS#12");
    }

    private static void createEmptyPkcs12(Path file, char[] password) throws Exception {
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        try (var output = Files.newOutputStream(file)) {
            keyStore.store(output, password);
        }
    }
}

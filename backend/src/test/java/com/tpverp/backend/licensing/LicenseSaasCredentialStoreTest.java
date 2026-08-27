package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseSaasCredentialStoreTest {

    @TempDir
    Path directory;

    @Test
    void guardaLeeYSobrescribeTokenProtegido() throws Exception {
        var store = new LicenseSaasCredentialStore(directory, new PlainProtector());

        assertThat(store.readToken()).isEmpty();

        store.writeToken("token-uno");
        assertThat(store.readToken()).contains("token-uno");

        store.writeToken("token-dos");
        assertThat(store.readToken()).contains("token-dos");
        assertThat(Files.readString(directory.resolve("saas-installation-token.dpapi")))
                .isEqualTo("token-dos");
    }

    @Test
    void persisteRecuperacionCifradaAntesDelEnlaceYLaLimpiaSinBorrarLaCredencial() throws Exception {
        var store = new LicenseSaasCredentialStore(directory, new XorProtector());

        String first = store.getOrCreateLinkRecoveryToken();
        String recoveredAfterRestart = new LicenseSaasCredentialStore(
                directory, new XorProtector()).getOrCreateLinkRecoveryToken();

        assertThat(recoveredAfterRestart).isEqualTo(first);
        assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
        assertThat(java.util.Arrays.equals(
                Files.readAllBytes(directory.resolve("saas-link-recovery-token.dpapi")),
                first.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();

        store.writeToken("installation-token");
        store.clearLinkRecoveryToken();

        assertThat(store.readLinkRecoveryToken()).isEmpty();
        assertThat(store.readToken()).contains("installation-token");
    }

    private static class PlainProtector implements SecretProtector {

        @Override
        public byte[] protect(byte[] plaintext) {
            return plaintext.clone();
        }

        @Override
        public byte[] unprotect(byte[] protectedValue) {
            return protectedValue.clone();
        }
    }

    private static class XorProtector implements SecretProtector {

        @Override
        public byte[] protect(byte[] plaintext) {
            return xor(plaintext);
        }

        @Override
        public byte[] unprotect(byte[] protectedValue) {
            return xor(protectedValue);
        }

        private byte[] xor(byte[] value) {
            byte[] transformed = value.clone();
            for (int index = 0; index < transformed.length; index++) {
                transformed[index] ^= (byte) 0xA5;
            }
            return transformed;
        }
    }
}

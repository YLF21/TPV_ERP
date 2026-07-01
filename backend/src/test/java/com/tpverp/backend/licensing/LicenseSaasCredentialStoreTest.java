package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.Path;
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
}

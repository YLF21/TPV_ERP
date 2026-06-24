package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifactuCertificateSecretStoreTest {

    @TempDir Path root;

    @Test
    void protectsWritesReadsAndDeletesPrivateKey() throws Exception {
        var store = new VerifactuCertificateSecretStore(root, new XorProtector());
        var privateKey = new byte[] {1, 2, 3, 4};

        var relative = store.write(UUID.randomUUID(), UUID.randomUUID(), privateKey);

        assertThat(store.read(relative)).containsExactly(privateKey);
        assertThat(Files.readAllBytes(root.resolve(relative)))
                .containsExactly(91, 88, 89, 94);
        store.delete(relative);
        assertThat(root.resolve(relative)).doesNotExist();
    }

    @Test
    void rejectsPathsOutsideSecretRoot() {
        var store = new VerifactuCertificateSecretStore(root, new XorProtector());

        assertThatThrownBy(() -> store.read("../outside.dpapi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ruta de certificado fuera del directorio seguro");
    }

    private static final class XorProtector implements SecretProtector {
        @Override
        public byte[] protect(byte[] plaintext) {
            return xor(plaintext);
        }

        @Override
        public byte[] unprotect(byte[] protectedValue) {
            return xor(protectedValue);
        }

        private static byte[] xor(byte[] value) {
            var result = value.clone();
            for (var index = 0; index < result.length; index++) {
                result[index] ^= 0x5A;
            }
            return result;
        }
    }
}

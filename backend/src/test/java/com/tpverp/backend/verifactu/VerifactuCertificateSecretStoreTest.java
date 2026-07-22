package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
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

    @Test
    void rejectsAbsoluteAndUnexpectedSecretPaths() {
        var store = new VerifactuCertificateSecretStore(root, new XorProtector());
        var absolute = root.resolve(UUID.randomUUID().toString())
                .resolve(UUID.randomUUID().toString())
                .resolve("private-key.dpapi");

        assertThatThrownBy(() -> store.read(absolute.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La ruta de certificado debe ser relativa");
        assertThatThrownBy(() -> store.read("otro/directorio/secreto.dpapi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Esquema de ruta de certificado no valido");
    }

    @Test
    void rejectsSymbolicLinksInsideTheSecretHierarchyWhenSupported() throws Exception {
        var outside = Files.createDirectory(root.resolve("outside-real"));
        var companyId = UUID.randomUUID();
        var certificateId = UUID.randomUUID();
        var companyDirectory = root.resolve(companyId.toString());
        try {
            Files.createSymbolicLink(companyDirectory, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            Assumptions.abort("El sistema no permite crear enlaces simbolicos para esta prueba");
        }
        var store = new VerifactuCertificateSecretStore(root, new XorProtector());

        assertThatThrownBy(() -> store.write(companyId, certificateId, new byte[] {1}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enlace o reparse point");
    }

    @Test
    void appliesOwnerOnlyPermissionsOnPosixFileSystems() throws Exception {
        Assumptions.assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var store = new VerifactuCertificateSecretStore(root, new XorProtector());

        var relative = store.write(UUID.randomUUID(), UUID.randomUUID(), new byte[] {1, 2});

        assertThat(Files.getPosixFilePermissions(root)).isEqualTo(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        assertThat(Files.getPosixFilePermissions(root.resolve(relative))).isEqualTo(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
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

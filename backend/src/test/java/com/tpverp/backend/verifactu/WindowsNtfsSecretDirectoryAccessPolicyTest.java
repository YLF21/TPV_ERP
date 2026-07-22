package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.jna.platform.win32.Advapi32Util;
import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsNtfsSecretDirectoryAccessPolicyTest {

    @TempDir Path root;

    @Test
    void protectsRootDirectoriesAndSecretFilesWithAnExplicitDacl() throws Exception {
        var policy = WindowsNtfsSecretDirectoryAccessPolicy.forCurrentAccount();
        var store = new VerifactuCertificateSecretStore(root, new XorProtector(), policy);

        var relative = store.write(UUID.randomUUID(), UUID.randomUUID(), new byte[] {1, 2, 3});
        var secret = root.resolve(relative);

        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(root)).isTrue();
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(secret)).isTrue();
        policy.verifyDirectory(root);
        policy.verifyDirectory(secret.getParent());
        policy.verifyFile(secret);
    }

    @Test
    void refusesASecretAfterItsAclHasBeenBroadened() throws Exception {
        var policy = WindowsNtfsSecretDirectoryAccessPolicy.forCurrentAccount();
        var store = new VerifactuCertificateSecretStore(root, new XorProtector(), policy);
        var relative = store.write(UUID.randomUUID(), UUID.randomUUID(), new byte[] {4, 5, 6});
        var secret = root.resolve(relative);
        var view = Files.getFileAttributeView(
                secret, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var everyoneName = Advapi32Util.getAccountBySid("S-1-1-0").fqn;
        var everyone = secret.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(everyoneName);
        var broadened = new java.util.ArrayList<>(view.getAcl());
        broadened.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(everyone)
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA))
                .build());
        view.setAcl(broadened);

        assertThatThrownBy(() -> store.read(relative))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACL NTFS");
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

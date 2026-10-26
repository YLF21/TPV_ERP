package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.jna.platform.win32.Advapi32Util;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class LicenseSaasCredentialStoreWindowsTest {
    @TempDir Path temporary;

    @Test
    void creationAndRotationKeepProtectedDaclAndDurableRecoveryIdentity() throws Exception {
        Path root = temporary.resolve("keys");
        var store = store(root);
        store.writeToken("first-installation-token");
        Path installation = root.resolve("saas-installation-token.dpapi");
        assertProtected(root, installation);
        String recovery = store.getOrCreateLinkRecoveryToken();
        Path recoveryFile = root.resolve("saas-link-recovery-token.dpapi");
        byte[] recoveryBytes = Files.readAllBytes(recoveryFile);
        assertProtected(root, recoveryFile);

        store.writeToken("rotated-installation-token");
        assertProtected(root, installation);
        var restarted = store(root);
        assertThat(restarted.readToken()).contains("rotated-installation-token");
        assertThat(restarted.getOrCreateLinkRecoveryToken()).isEqualTo(recovery);
        assertThat(Files.readAllBytes(recoveryFile)).containsExactly(recoveryBytes);
        restarted.clearLinkRecoveryToken();
        assertThat(recoveryFile).doesNotExist();
        assertThat(restarted.readToken()).contains("rotated-installation-token");
        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("saas-installation-token.dpapi");
        }
    }

    @Test
    void existingRecoveryIsReusedWithoutRewritingItsBytesOrCanonicalizingItsAcl() throws Exception {
        Path root = temporary.resolve("keys");
        TransferSecretDirectory.forCurrentAccount(root);
        Path recovery = Files.writeString(root.resolve("saas-link-recovery-token.dpapi"), "legacy-recovery");
        byte[] original = Files.readAllBytes(recovery);
        var owner = Files.getOwner(recovery, LinkOption.NOFOLLOW_LINKS);
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(recovery)).isFalse();

        // Legacy startup remains governed by the unchanged installer preflight;
        // reading a persisted recovery token must never mint another credential.
        assertThat(store(root).getOrCreateLinkRecoveryToken()).isEqualTo("legacy-recovery");
        assertThat(Files.readAllBytes(recovery)).containsExactly(original);
        assertThat(Files.getOwner(recovery, LinkOption.NOFOLLOW_LINKS)).isEqualTo(owner);
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(recovery)).isFalse();
    }

    @Test
    void rotationDoesNotRepairAnInheritedLegacyTarget() throws Exception {
        Path root = temporary.resolve("keys");
        TransferSecretDirectory.forCurrentAccount(root);
        Path token = Files.writeString(root.resolve("saas-installation-token.dpapi"), "legacy-token");
        assertThatThrownBy(() -> store(root).writeToken("must-not-replace"))
                .isInstanceOf(IllegalStateException.class).hasMessage("No se pudo guardar el token SaaS");
        assertThat(Files.readString(token)).isEqualTo("legacy-token");
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(token)).isFalse();
        try (var files = Files.list(root)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void rotationRejectsAnUnexpectedReaderAndPreservesExistingCredential() throws Exception {
        Path root = temporary.resolve("keys");
        var store = store(root);
        store.writeToken("existing-token");
        Path file = root.resolve("saas-installation-token.dpapi");
        var view = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var everyone = file.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(Advapi32Util.getAccountBySid("S-1-1-0").fqn);
        var altered = new ArrayList<>(view.getAcl());
        altered.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(AclEntryPermission.READ_DATA).build());
        view.setAcl(altered);

        assertThatThrownBy(() -> store.writeToken("must-not-replace"))
                .isInstanceOf(IllegalStateException.class).hasMessage("No se pudo guardar el token SaaS");
        assertThat(Files.readString(file)).isEqualTo("existing-token");
        assertThat(view.getAcl()).containsAll(altered);
        try (var files = Files.list(root)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void doesNotCanonicalizeAnUnprotectedExistingRootDuringRuntimeWrite() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("inherited-root"));
        var before = Files.getFileAttributeView(root, AclFileAttributeView.class).getAcl();
        assertThatThrownBy(() -> store(root).writeToken("must-not-write"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.getFileAttributeView(root, AclFileAttributeView.class).getAcl()).isEqualTo(before);
        assertThat(root.resolve("saas-installation-token.dpapi")).doesNotExist();
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(root)).isFalse();
    }

    private static LicenseSaasCredentialStore store(Path root) {
        return new LicenseSaasCredentialStore(root, new SecretProtector() {
            @Override public byte[] protect(byte[] value) { return value.clone(); }
            @Override public byte[] unprotect(byte[] value) { return value.clone(); }
        }, Advapi32Util.getAccountByName(Advapi32Util.getUserName()).fqn);
    }

    private static void assertProtected(Path root, Path file) {
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(root)).isTrue();
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(file)).isTrue();
        TransferSecretDirectory.forCurrentAccount(root).verifyFile(file.getFileName().toString());
    }
}

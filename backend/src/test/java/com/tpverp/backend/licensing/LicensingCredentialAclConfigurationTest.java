package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.jna.platform.win32.Advapi32Util;
import com.tpverp.backend.verifactu.TransferSecretDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LicensingCredentialAclConfigurationTest {
    @TempDir Path temporary;
    private final LicensingConfiguration configuration = new LicensingConfiguration();
    private final String portableKey = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void portableModeKeepsExistingDirectoryAndDoesNotResolveAWindowsServiceAccount() {
        var store = configuration.licenseSaasCredentialStore(
                temporary, portableKey, "PORTABLE", "not-an-installed-Windows-account");
        store.writeToken("portable-token");
        assertThat(store.readToken()).contains("portable-token");
    }

    @Test
    void unknownAclModeIsRejectedInsteadOfFallingBackToPortable() {
        assertThatThrownBy(() -> configuration.licenseSaasCredentialStore(
                temporary, portableKey, "STRCIT", "runtime-account"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PORTABLE o STRICT");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void strictModeUsesConfiguredRuntimeAccountInsteadOfMaintenanceCaller() throws Exception {
        Path root = temporary.resolve("keys");
        var current = Advapi32Util.getAccountByName(Advapi32Util.getUserName()).fqn;
        var store = configuration.licenseSaasCredentialStore(root, portableKey, "STRICT", current);
        store.writeToken("original-token");
        byte[] original = Files.readAllBytes(root.resolve("saas-installation-token.dpapi"));

        String differentAccount = Advapi32Util.getAccountBySid("S-1-5-32-545").fqn;
        var mismatched = configuration.licenseSaasCredentialStore(root, portableKey, "STRICT", differentAccount);
        assertThatThrownBy(() -> mismatched.writeToken("must-not-replace"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(root.resolve("saas-installation-token.dpapi"))).containsExactly(original);
        new TransferSecretDirectory(root, current).verifyFile("saas-installation-token.dpapi");
        assertThat(store.readToken()).contains("original-token");
    }
}

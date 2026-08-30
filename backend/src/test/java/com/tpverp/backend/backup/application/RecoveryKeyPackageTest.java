package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RecoveryKeyPackageTest {

    private static final int ITERATIONS = 120_000;

    private final RecoveryKeyPackage recoveryPackages = new RecoveryKeyPackage();

    @Test
    void createsRandomBrkProtectedByTheAdminPassword() throws Exception {
        byte[] firstPackage = recoveryPackages.create("admin-secret".toCharArray(), ITERATIONS);
        byte[] secondPackage = recoveryPackages.create("admin-secret".toCharArray(), ITERATIONS);

        byte[] firstBrk = recoveryPackages.open(firstPackage, "admin-secret".toCharArray());
        byte[] secondBrk = recoveryPackages.open(secondPackage, "admin-secret".toCharArray());

        assertThat(firstBrk).hasSize(32);
        assertThat(secondBrk).hasSize(32);
        assertThat(secondBrk).isNotEqualTo(firstBrk);
        assertThat(firstPackage).isNotEqualTo(secondPackage);
        assertThat(recoveryPackages.inspect(firstPackage))
            .isEqualTo(new RecoveryKeyPackage.Info(1, ITERATIONS, 16, 12));
    }

    @Test
    void rejectsWrongPasswordAndTampering() throws Exception {
        byte[] recoveryPackage = recoveryPackages.create("correct".toCharArray(), ITERATIONS);
        byte[] tampered = Arrays.copyOf(recoveryPackage, recoveryPackage.length);
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> recoveryPackages.open(recoveryPackage, "wrong".toCharArray()))
            .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> recoveryPackages.open(tampered, "correct".toCharArray()))
            .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void rewrapsTheSameBrkWithoutChangingBackupKeys() throws Exception {
        byte[] original = recoveryPackages.create("old-password".toCharArray(), ITERATIONS);
        byte[] brkBefore = recoveryPackages.open(original, "old-password".toCharArray());

        byte[] rewrapped = recoveryPackages.rewrap(
            original,
            "old-password".toCharArray(),
            "new-password".toCharArray(),
            150_000);

        assertThat(recoveryPackages.open(rewrapped, "new-password".toCharArray()))
            .isEqualTo(brkBefore);
        assertThatThrownBy(() -> recoveryPackages.open(rewrapped, "old-password".toCharArray()))
            .isInstanceOf(GeneralSecurityException.class);
        assertThat(recoveryPackages.inspect(rewrapped).iterations()).isEqualTo(150_000);
    }

    @Test
    void rejectsUnreasonableIterationCounts() {
        assertThatThrownBy(() -> recoveryPackages.create("password".toCharArray(), 99_999))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("100000");
    }

    @Test
    void v2IsIndependentFromAdminAndLegacyRemainsReadable() throws Exception {
        char[] recovery = "a-strong-recovery-passphrase".toCharArray();
        byte[] packageV2 = recoveryPackages.createV2(recovery, ITERATIONS);
        assertThat(recoveryPackages.inspect(packageV2).version()).isEqualTo(2);
        assertThat(recoveryPackages.openAny(packageV2, recovery)).hasSize(32);
        assertThatThrownBy(() -> recoveryPackages.openAny(packageV2, "admin-pin".toCharArray()))
                .isInstanceOf(GeneralSecurityException.class);
        byte[] legacy = recoveryPackages.create("legacy-admin".toCharArray(), ITERATIONS);
        assertThat(recoveryPackages.openAny(legacy, "legacy-admin".toCharArray())).hasSize(32);
    }

    @Test
    void generatedRecoveryCodeHasHighEntropyAndNoShortSecretIsAccepted() throws Exception {
        char[] code = recoveryPackages.generateRecoveryCode();
        assertThat(code).hasSizeGreaterThanOrEqualTo(40);
        assertThatThrownBy(() -> recoveryPackages.createV2("short".toCharArray(), ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAdminRewrapOfV2() throws Exception {
        byte[] packageV2 = recoveryPackages.createV2("independent-recovery".toCharArray(), ITERATIONS);
        assertThatThrownBy(() -> recoveryPackages.rewrap(packageV2,
                "admin".toCharArray(), "new-admin".toCharArray(), ITERATIONS))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("v2");
    }
}

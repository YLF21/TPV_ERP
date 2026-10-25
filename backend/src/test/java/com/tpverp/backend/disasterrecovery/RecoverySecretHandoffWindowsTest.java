package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;
import com.tpverp.backend.verifactu.TransferSecretDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class RecoverySecretHandoffWindowsTest {
    @TempDir Path temporary;

    @Test
    void recoverySecretWriterKeepsCanonicalProtectedAclWhenReplacingFiles() throws Exception {
        Path keys=temporary.resolve("keys");
        var directory=TransferSecretDirectory.forCurrentAccount(keys);
        Path target=keys.resolve("backup-brk.dpapi");
        RecoverySecretHandoff.writeSecretFile(target,new byte[]{1,2},Advapi32Util.getUserName());
        var owner=Files.getOwner(target);
        RecoverySecretHandoff.writeSecretFile(target,new byte[]{3,4},Advapi32Util.getUserName());
        assertThat(Files.readAllBytes(target)).containsExactly(3,4);
        assertThat(Files.getOwner(target)).isEqualTo(owner);
        directory.verifyFile("backup-brk.dpapi");
        assertThat(Advapi32Util.getFileSecurityDescriptor(target.toFile(),false).Control & WinNT.SE_DACL_PROTECTED).isNotZero();
    }
}

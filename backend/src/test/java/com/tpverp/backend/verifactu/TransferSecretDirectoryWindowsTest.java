package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.jna.platform.win32.Advapi32Util;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class TransferSecretDirectoryWindowsTest {
    @TempDir Path temporary;

    @Test
    void protectsTemporaryBeforeWritingAndPreservesProtectionAcrossAtomicReplacement() throws Exception {
        Path root=temporary.resolve("keys");
        var directory=TransferSecretDirectory.forCurrentAccount(root);
        Path pending=directory.createProtectedTemporary();
        assertThat(Files.size(pending)).isZero();
        assertProtectedDacl(pending);
        Files.delete(pending);

        directory.write("backup-brk.dpapi",new byte[]{1,2,3});
        Path file=root.resolve("backup-brk.dpapi");
        directory.write("backup-brk.dpapi",new byte[]{4,5,6});
        assertThat(Files.readAllBytes(file)).containsExactly(4,5,6);
        directory.verifyFile("backup-brk.dpapi");
        assertProtectedDacl(file);
    }

    @Test
    void normalizesOnlyEquivalentInheritedDaclWithoutChangingBytesOrOwner() throws Exception {
        Path root=temporary.resolve("keys");
        var directory=TransferSecretDirectory.forCurrentAccount(root);
        Path file=Files.write(root.resolve("backup-brk.dpapi"),new byte[]{9,8,7});
        var originalOwner=Files.getOwner(file,LinkOption.NOFOLLOW_LINKS);
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(file)).isFalse();

        assertThat(TransferSecretDirectory.normalizeInheritedFiles(root,Advapi32Util.getUserName(),List.of("backup-brk.dpapi"))).isTrue();
        assertThat(Files.readAllBytes(file)).containsExactly(9,8,7);
        assertThat(Files.getOwner(file,LinkOption.NOFOLLOW_LINKS)).isEqualTo(originalOwner);
        directory.verifyFile("backup-brk.dpapi");
        assertProtectedDacl(file);
        assertThat(directory.normalizeInheritedFiles(List.of("backup-brk.dpapi"))).isFalse();
    }

    @Test
    void rejectsUnexpectedReaderBeforeChangingAnyEarlierFile() throws Exception {
        Path root=temporary.resolve("keys");
        var directory=TransferSecretDirectory.forCurrentAccount(root);
        Path first=Files.write(root.resolve("first.dpapi"),new byte[]{1});
        Path second=Files.write(root.resolve("second.dpapi"),new byte[]{2});
        var view=Files.getFileAttributeView(second,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        var everyone=second.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(Advapi32Util.getAccountBySid("S-1-1-0").fqn);
        var altered=new ArrayList<>(view.getAcl());
        altered.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(AclEntryPermission.READ_DATA).build());
        view.setAcl(altered);

        assertThatThrownBy(()->directory.normalizeInheritedFiles(List.of("first.dpapi","second.dpapi")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("permisos no autorizados");
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(first)).isFalse();
        assertThat(Files.readAllBytes(first)).containsExactly(1);
        assertThat(Files.readAllBytes(second)).containsExactly(2);
        assertThat(view.getAcl()).containsAll(altered);
    }

    @Test
    void rejectsMissingOrNonFileTargetsBeforeChangingAnyAcl() throws Exception {
        Path root=temporary.resolve("keys");
        var directory=TransferSecretDirectory.forCurrentAccount(root);
        Path first=Files.write(root.resolve("first.dpapi"),new byte[]{3});
        assertThatThrownBy(()->directory.normalizeInheritedFiles(List.of("first.dpapi","missing.dpapi")))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(first)).isFalse();
        Files.createDirectory(root.resolve("directory"));
        assertThatThrownBy(()->directory.normalizeInheritedFiles(List.of("first.dpapi","directory")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("regular secret file");
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(first)).isFalse();
        assertThatThrownBy(()->directory.normalizeInheritedFiles(List.of("../outside.dpapi")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotNormalizeAnUnprotectedRootOrCreateAMissingRoot() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("inherited-root"));
        Path file=Files.write(root.resolve("secret.dpapi"),new byte[]{4});
        assertThatThrownBy(()->TransferSecretDirectory.normalizeInheritedFiles(root,Advapi32Util.getUserName(),List.of("secret.dpapi")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(file)).containsExactly(4);
        Path absent=temporary.resolve("absent-root");
        assertThatThrownBy(()->TransferSecretDirectory.normalizeInheritedFiles(absent,Advapi32Util.getUserName(),List.of("secret.dpapi")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(absent).doesNotExist();
    }

    private static void assertProtectedDacl(Path path) throws Exception {
        assertThat(WindowsNtfsSecretDirectoryAccessPolicy.daclIsProtected(path)).isTrue();
        Path windowsPowerShell=Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0");
        var process=new ProcessBuilder(windowsPowerShell.resolve("powershell.exe").toString(),"-NoProfile","-NonInteractive","-Command",
                "$ErrorActionPreference='Stop'; $acl=[IO.File]::GetAccessControl($env:TPV_ACL_TEST_FILE); "
                +"[Console]::WriteLine($acl.AreAccessRulesProtected); "
                +"[Console]::WriteLine($acl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access))");
        process.environment().put("TPV_ACL_TEST_FILE",path.toString());
        // Maven may inherit PowerShell 7 module paths; use only Windows PowerShell's own modules.
        process.environment().put("PSModulePath",windowsPowerShell.resolve("Modules").toString());
        var child=process.redirectErrorStream(true).start();
        String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(child.waitFor()).withFailMessage("ACL metadata query failed: %s",output).isZero();
        var metadata=output.lines().toList();
        assertThat(metadata).hasSize(2);
        assertThat(metadata.getFirst()).isEqualTo("True");
        assertThat(metadata.get(1)).startsWith("D:P");
    }
}

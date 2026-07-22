package com.tpverp.backend.verifactu;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

final class WindowsNtfsSecretDirectoryAccessPolicy implements SecretDirectoryAccessPolicy {

    static final String LOCAL_SYSTEM_SID = "S-1-5-18";
    static final String BUILTIN_ADMINISTRATORS_SID = "S-1-5-32-544";

    private final String applicationAccount;

    WindowsNtfsSecretDirectoryAccessPolicy(String applicationAccount) {
        if (applicationAccount == null || applicationAccount.isBlank()) {
            throw new IllegalArgumentException("La cuenta Windows del backend es obligatoria");
        }
        this.applicationAccount = applicationAccount.trim();
    }

    static WindowsNtfsSecretDirectoryAccessPolicy forCurrentAccount() {
        return new WindowsNtfsSecretDirectoryAccessPolicy(Advapi32Util.getUserName());
    }

    @Override
    public void prepareRoot(Path root) {
        try {
            Files.createDirectories(root);
            secureDirectory(root);
        } catch (IOException exception) {
            throw failure("No se pudo preparar el directorio NTFS de secretos VERI*FACTU", exception);
        }
    }

    @Override
    public void secureDirectory(Path directory) {
        apply(directory, true);
    }

    @Override
    public void secureFile(Path file) {
        apply(file, false);
    }

    @Override
    public void verifyDirectory(Path directory) {
        verify(directory, true);
    }

    @Override
    public void verifyFile(Path file) {
        verify(file, false);
    }

    private void apply(Path path, boolean directory) {
        verifyType(path, directory);
        var view = aclView(path);
        var expected = expectedAcl(path, directory);
        try {
            view.setAcl(expected);
        } catch (IOException exception) {
            throw failure("No se pudo aplicar la ACL NTFS del secreto VERI*FACTU", exception);
        }
        // AclFileAttributeView no expone el bit PROTECTED_DACL. Se establece
        // despues de reemplazar la lista para que Windows no vuelva a marcar
        // como heredadas las ACE procedentes del directorio padre.
        protectDacl(path);
        verify(path, directory);
    }

    private void verify(Path path, boolean directory) {
        verifyType(path, directory);
        if (!daclIsProtected(path)) {
            throw failure("La ACL NTFS del secreto VERI*FACTU hereda permisos", null);
        }
        try {
            var actual = new HashSet<>(aclView(path).getAcl());
            var expected = new HashSet<>(expectedAcl(path, directory));
            if (!actual.equals(expected)) {
                throw failure("La ACL NTFS del secreto VERI*FACTU contiene permisos no autorizados", null);
            }
        } catch (IOException exception) {
            throw failure("No se pudo verificar la ACL NTFS del secreto VERI*FACTU", exception);
        }
    }

    private List<AclEntry> expectedAcl(Path path, boolean directory) {
        var lookup = path.getFileSystem().getUserPrincipalLookupService();
        try {
            var principals = List.of(
                    lookup.lookupPrincipalByName(applicationAccount),
                    lookup.lookupPrincipalByName(accountNameForSid(LOCAL_SYSTEM_SID)),
                    lookup.lookupPrincipalByName(accountNameForSid(BUILTIN_ADMINISTRATORS_SID)));
            var unique = new ArrayList<UserPrincipal>();
            for (var principal : principals) {
                if (!unique.contains(principal)) {
                    unique.add(principal);
                }
            }
            return unique.stream().map(principal -> allowFullControl(principal, directory)).toList();
        } catch (IOException exception) {
            throw failure("No se pudo resolver la cuenta Windows autorizada para VERI*FACTU", exception);
        }
    }

    private static AclEntry allowFullControl(UserPrincipal principal, boolean directory) {
        var builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return builder.build();
    }

    private static String accountNameForSid(String sid) {
        return Advapi32Util.getAccountBySid(sid).fqn;
    }

    private static AclFileAttributeView aclView(Path path) {
        var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw failure("El sistema de archivos no ofrece ACL NTFS para los secretos VERI*FACTU", null);
        }
        return view;
    }

    private static void protectDacl(Path path) {
        var descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
        descriptor.Control = (short) (descriptor.Control | WinNT.SE_DACL_PROTECTED);
        Advapi32Util.setFileSecurityDescriptor(
                path.toFile(), descriptor,
                false, false, true, false, true, false);
    }

    static boolean daclIsProtected(Path path) {
        var descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
        return (descriptor.Control & WinNT.SE_DACL_PROTECTED) != 0;
    }

    private static void verifyType(Path path, boolean directory) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || directory != Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("La ruta NTFS del secreto VERI*FACTU no tiene el tipo esperado", null);
        }
    }

    private static IllegalStateException failure(String message, Exception cause) {
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}

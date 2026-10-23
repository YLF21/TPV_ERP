package com.tpverp.backend.verifactu;

import java.nio.file.Path;
import java.nio.file.attribute.AclEntryPermission;
import java.util.EnumSet;

/** Test provisioning runs as an ordinary Windows user; restrict the service account after writing. */
public final class TransferTlsFixtureAccess {
    private TransferTlsFixtureAccess() { }

    public static void protect(Path directory, String account) {
        var read = EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_ACL,
                AclEntryPermission.EXECUTE, AclEntryPermission.SYNCHRONIZE);
        var policy = new WindowsNtfsSecretDirectoryAccessPolicy(account, read);
        policy.secureFile(directory.resolve("private-key.pem"));
        policy.secureDirectory(directory);
    }

    public static void restoreForCleanup(Path directory, String account) {
        // The test owns these freshly generated files; restore its deletion rights for @TempDir cleanup.
        var policy = new WindowsNtfsSecretDirectoryAccessPolicy(account);
        policy.secureDirectory(directory);
        policy.secureFile(directory.resolve("private-key.pem"));
    }
}

package com.tpverp.backend.verifactu;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

/** Maintenance uses the same protected NTFS policy as the installed certificate store. */
public final class TransferSecretDirectory {
    private final Path root;
    private final SecretDirectoryAccessPolicy policy;
    public TransferSecretDirectory(Path root,String serviceAccount) {
        this(root,policy(serviceAccount));
    }
    public static TransferSecretDirectory forCurrentAccount(Path root) {
        return new TransferSecretDirectory(root,com.sun.jna.Platform.isWindows()
                ?WindowsNtfsSecretDirectoryAccessPolicy.forCurrentAccount():new PortableSecretDirectoryAccessPolicy());
    }
    private TransferSecretDirectory(Path root,SecretDirectoryAccessPolicy policy) {
        this.root=root.toAbsolutePath().normalize();
        this.policy=policy;
        SecretPathSafety.rejectLinksAndReparsePoints(this.root);
        if(Files.exists(this.root,LinkOption.NOFOLLOW_LINKS))policy.verifyDirectory(this.root);
        else policy.prepareRoot(this.root);
    }
    public static TransferSecretDirectory tls(Path root,String account) {
        var read=java.util.EnumSet.of(java.nio.file.attribute.AclEntryPermission.READ_DATA,
                java.nio.file.attribute.AclEntryPermission.READ_NAMED_ATTRS,java.nio.file.attribute.AclEntryPermission.READ_ATTRIBUTES,
                java.nio.file.attribute.AclEntryPermission.READ_ACL,java.nio.file.attribute.AclEntryPermission.EXECUTE,
                java.nio.file.attribute.AclEntryPermission.SYNCHRONIZE);
        return new TransferSecretDirectory(root,com.sun.jna.Platform.isWindows()
                ?new WindowsNtfsSecretDirectoryAccessPolicy(account,read):new PortableSecretDirectoryAccessPolicy());
    }
    static SecretDirectoryAccessPolicy policy(String serviceAccount) {
        return com.sun.jna.Platform.isWindows()
                ?new WindowsNtfsSecretDirectoryAccessPolicy(serviceAccount)
                :new PortableSecretDirectoryAccessPolicy();
    }
    public void verifyFile(String name) {policy.verifyFile(file(name));}
    /** The empty temporary is protected before a caller can write any secret bytes. */
    public Path createProtectedTemporary() throws Exception {
        SecretPathSafety.rejectLinksAndReparsePoints(root);
        policy.verifyDirectory(root);
        Path temporary=Files.createTempFile(root,".transfer-",".tmp");
        try {
            policy.secureFile(temporary);
            return temporary;
        } catch(Exception failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    /**
     * Repairs only the legacy inheritance bit, never an unexpected principal or permission.
     * All named files are validated before changing any ACL. Neither content nor ownership is written.
     */
    public static boolean normalizeInheritedFiles(Path root,String serviceAccount,Collection<String> names) throws Exception {
        SecretPathSafety.rejectLinksAndReparsePoints(root);
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Existing protected secret directory required");
        return new TransferSecretDirectory(root,serviceAccount).normalizeInheritedFiles(names);
    }

    public boolean normalizeInheritedFiles(Collection<String> names) throws Exception {
        SecretPathSafety.rejectLinksAndReparsePoints(root);
        policy.verifyDirectory(root);
        if(names==null || names.isEmpty())throw new IllegalArgumentException("An explicit secret filename allowlist is required");
        var unique=new HashSet<String>();
        var targets=new ArrayList<ExistingFile>();
        for(String name:names) {
            if(!unique.add(name))throw new IllegalArgumentException("Duplicate secret filename");
            Path target=file(name);
            var attributes=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile() || attributes.isOther())throw new IllegalStateException("Existing regular secret file required");
            if(policy instanceof WindowsNtfsSecretDirectoryAccessPolicy windows)windows.verifyFileForInheritanceNormalization(target);
            else policy.verifyFile(target);
            targets.add(new ExistingFile(target,attributes.fileKey(),Files.getOwner(target,LinkOption.NOFOLLOW_LINKS)));
        }
        boolean changed=false;
        for(var target:targets) {
            SecretPathSafety.rejectLinksAndReparsePoints(target.path());
            var attributes=Files.readAttributes(target.path(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile() || !Objects.equals(attributes.fileKey(),target.fileKey())
                    || !Files.getOwner(target.path(),LinkOption.NOFOLLOW_LINKS).equals(target.owner()))
                throw new IllegalStateException("Secret file changed during ACL validation");
            if(policy instanceof WindowsNtfsSecretDirectoryAccessPolicy windows)changed|=windows.protectEquivalentInheritedFile(target.path());
            else policy.verifyFile(target.path());
            if(!Files.getOwner(target.path(),LinkOption.NOFOLLOW_LINKS).equals(target.owner()))throw new IllegalStateException("Secret ownership changed during ACL protection");
        }
        return changed;
    }
    private record ExistingFile(Path path,Object fileKey,UserPrincipal owner) { }

    public void write(String name,byte[] bytes)throws Exception {
        Path target=file(name);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))policy.verifyFile(target);
        Path temporary=createProtectedTemporary();
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            policy.verifyFile(target);
        }finally{Files.deleteIfExists(temporary);}
    }
    private Path file(String name) {
        if(name==null || !name.matches("[a-zA-Z0-9._-]+") || name.equals(".") || name.equals(".."))throw new IllegalArgumentException("Invalid secret filename");
        Path target=root.resolve(name);SecretPathSafety.rejectLinksAndReparsePoints(target);return target;
    }
}

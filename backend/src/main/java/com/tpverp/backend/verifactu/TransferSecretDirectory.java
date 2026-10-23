package com.tpverp.backend.verifactu;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

/** Maintenance uses the same protected NTFS policy as the installed certificate store. */
public final class TransferSecretDirectory {
    private final Path root;
    private final SecretDirectoryAccessPolicy policy;
    public TransferSecretDirectory(Path root,String serviceAccount) {
        this(root,policy(serviceAccount));
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
    public void write(String name,byte[] bytes)throws Exception {
        Path target=file(name);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))policy.verifyFile(target);
        Path temporary=Files.createTempFile(root,".transfer-",".tmp");
        try {
            policy.secureFile(temporary);
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

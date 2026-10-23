package com.tpverp.backend.disasterrecovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.shared.crypto.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;

/** Machine DPAPI + protected key-directory ACL is the one-boot bridge to service-user DPAPI. */
final class RecoverySecretHandoff {
    static final String FILE="disaster-recovery-destination.dpapi";
    private static final ObjectMapper JSON=new ObjectMapper();
    private RecoverySecretHandoff(){}
    static ObjectNode read(Path keys) throws Exception {
        requireProtectedDirectory(keys);
        var path=keys.resolve(FILE);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>34L*1024*1024)throw new IllegalStateException("Transfer handoff missing or invalid");
        byte[] clear=new WindowsMachineDpapiSecretProtector().unprotect(Files.readAllBytes(path));
        try{return (ObjectNode)JSON.readTree(clear);}finally{Arrays.fill(clear,(byte)0);}
    }
    static void write(Path keys,ObjectNode value)throws Exception {
        requireProtectedDirectory(keys);
        byte[] clear=JSON.writeValueAsBytes(value);
        try{writeFile(keys.resolve(FILE),new WindowsMachineDpapiSecretProtector().protect(clear));}
        finally{Arrays.fill(clear,(byte)0);}
    }
    static void requireProtectedDirectory(Path keys)throws Exception {
        Files.createDirectories(keys);
        for(Path current=keys.toAbsolutePath().normalize();current!=null;current=current.getParent()) {
            var attributes=Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attributes.isSymbolicLink() || attributes.isOther())throw new IllegalStateException("Transfer secret directory cannot contain links/reparse points");
        }
        var acl=Files.getFileAttributeView(keys,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IllegalStateException("Transfer secret handoff requires NTFS ACL protection");
        var allowed=new java.util.HashSet<String>(java.util.Set.of("S-1-5-18","S-1-5-32-544",
                com.sun.jna.platform.win32.Advapi32Util.getAccountByName(com.sun.jna.platform.win32.Advapi32Util.getUserName()).sidString));
        try{allowed.add(com.sun.jna.platform.win32.Advapi32Util.getAccountByName(System.getenv().getOrDefault("TPV_BACKEND_SERVICE_ACCOUNT","NT SERVICE\\TPVERPBackend")).sidString);}catch(RuntimeException absentService){/* Development account only. */}
        for(var entry:acl.getAcl())if(entry.type()==java.nio.file.attribute.AclEntryType.ALLOW
                && entry.permissions().contains(java.nio.file.attribute.AclEntryPermission.READ_DATA)
                && !allowed.contains(com.sun.jna.platform.win32.Advapi32Util.getAccountByName(entry.principal().getName()).sidString))
            throw new IllegalStateException("Transfer handoff directory ACL allows an unrelated reader");
    }
    static void writeFile(Path target,byte[] bytes)throws Exception {
        Files.createDirectories(target.getParent());var temporary=Files.createTempFile(target.getParent(),".handoff-",".tmp");
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
}

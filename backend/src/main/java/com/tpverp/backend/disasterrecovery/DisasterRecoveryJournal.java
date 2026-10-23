package com.tpverp.backend.disasterrecovery;

import java.nio.file.*;
import java.util.UUID;

/** Survives database replacement and blocks all normal startup until central authorization is verified. */
public final class DisasterRecoveryJournal {
    public static final String FREEZE="disaster-recovery.freeze";
    private DisasterRecoveryJournal() { }
    public static void freeze(Path keys,UUID recovery) throws Exception {
        Files.createDirectories(keys);Path path=keys.resolve(FREEZE);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {if(!Files.readString(path).equals(recovery.toString()))throw new IllegalStateException("RECOVERY_JOURNAL_CONFLICT");return;}
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            var bytes=java.nio.ByteBuffer.wrap(recovery.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
    }
    public static void release(Path keys,UUID recovery) throws Exception {
        Path path=keys.resolve(FREEZE);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !Files.readString(path).equals(recovery.toString()))throw new IllegalStateException("RECOVERY_JOURNAL_CONFLICT");
        Files.delete(path);
    }
}

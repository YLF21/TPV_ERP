package com.tpverp.backend.servertransfer;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Independent of the database: restoring an old dump cannot revive a frozen host. */
@Component
public final class ServerTransferJournal {
    private final Path path;
    public ServerTransferJournal(@Value("${tpv.installation.key-directory}") Path keys) {
        path=keys.toAbsolutePath().normalize().resolve("server-transfer.freeze");
    }
    public boolean frozen() { return Files.exists(path,LinkOption.NOFOLLOW_LINKS); }
    public boolean recoveryFrozen() { return Files.exists(path.resolveSibling(com.tpverp.backend.disasterrecovery.DisasterRecoveryJournal.FREEZE),LinkOption.NOFOLLOW_LINKS); }
    public synchronized void freeze(UUID id) {
        try {
            Files.createDirectories(path.getParent());
            if (frozen()) {
                if (!Files.readString(path).equals(id.toString())) throw new IllegalStateException("TRANSFER_JOURNAL_CONFLICT");
                return;
            }
            try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                channel.write(ByteBuffer.wrap(id.toString().getBytes(StandardCharsets.US_ASCII))); channel.force(true);
            }
        } catch(java.io.IOException e) { throw new IllegalStateException("TRANSFER_JOURNAL_FAILED",e); }
    }
    public synchronized void releaseAfterCentralCancellation(UUID id) {
        try {
            if (!frozen()) return;
            if (!Files.readString(path).equals(id.toString())) throw new IllegalStateException("TRANSFER_JOURNAL_CONFLICT");
            Files.delete(path);
        } catch(java.io.IOException e) { throw new IllegalStateException("TRANSFER_JOURNAL_FAILED",e); }
    }
}

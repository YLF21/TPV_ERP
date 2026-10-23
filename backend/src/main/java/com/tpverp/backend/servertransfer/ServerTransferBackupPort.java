package com.tpverp.backend.servertransfer;

import java.nio.file.Path;
import java.util.UUID;

public interface ServerTransferBackupPort {
    Artifact createFinalBackup(UUID transferId, UUID installationId, String destinationKeyFingerprint);
    void ensureCloudCopy(Path file);
    record Artifact(Path artifactPath, String sha256, long sizeBytes) {}
}

package com.tpverp.saas.backup;

import java.time.Instant;
import java.util.UUID;

public record CloudBackupDescriptor(UUID backupId, UUID installationId, long sequenceNumber,
        Instant capturedAt, String applicationVersion, int formatVersion, int portableSecretsVersion,
        long sizeBytes, String sha256, String recoveryKeySha256, int recoveryKeyVersion) { }

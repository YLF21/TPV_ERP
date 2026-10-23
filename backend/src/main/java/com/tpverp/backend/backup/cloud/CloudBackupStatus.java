package com.tpverp.backend.backup.cloud;

import java.time.Instant;

public record CloudBackupStatus(String state, int attempts, Instant uploadedAt,
        Instant nextAttemptAt, String errorReason) { }

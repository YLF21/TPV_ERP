package com.tpverp.backend.security.gestion;

import java.time.Instant;

public final class GestionGroupUnlockThrottledException extends RuntimeException {
    public static final String CODE = "GESTION_GROUP_UNLOCK_THROTTLED";

    private final Instant blockedUntil;
    private final long retryAfterSeconds;

    public GestionGroupUnlockThrottledException(Instant blockedUntil, long retryAfterSeconds) {
        super(CODE);
        this.blockedUntil = blockedUntil;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Instant blockedUntil() {
        return blockedUntil;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

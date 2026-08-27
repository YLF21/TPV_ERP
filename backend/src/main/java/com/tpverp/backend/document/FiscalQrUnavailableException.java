package com.tpverp.backend.document;

import java.util.Objects;
import java.util.UUID;

/**
 * Stops a fiscal print job when its frozen QR cannot be used safely.
 *
 * <p>The fiscal document is already committed at this point. Retrying this
 * operation only retries printing; it never creates or mutates the fiscal
 * record.</p>
 */
public final class FiscalQrUnavailableException extends IllegalStateException {

    public static final String CODE = "FISCAL_QR_UNAVAILABLE";

    private final UUID documentId;
    private final Reason reason;

    public FiscalQrUnavailableException(UUID documentId, Reason reason) {
        this(documentId, reason, null);
    }

    public FiscalQrUnavailableException(UUID documentId, Reason reason, Throwable cause) {
        super("message.fiscal_qr_unavailable", cause);
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public UUID documentId() {
        return documentId;
    }

    public Reason reason() {
        return reason;
    }

    public boolean retryable() {
        return true;
    }

    public enum Reason {
        FROZEN_SNAPSHOT_MISSING,
        FROZEN_SNAPSHOT_INVALID,
        FROZEN_SNAPSHOT_HASH_MISMATCH,
        FROZEN_ISSUER_IDENTITY_MISSING,
        IMAGE_GENERATION_FAILED
    }
}

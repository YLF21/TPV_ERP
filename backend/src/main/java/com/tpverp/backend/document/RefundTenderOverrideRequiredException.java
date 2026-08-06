package com.tpverp.backend.document;

/** Signals a recoverable checkout decision that requires explicit authorization. */
public final class RefundTenderOverrideRequiredException extends RuntimeException {
    public static final String CODE = "REFUND_TENDER_OVERRIDE_REQUIRED";

    public RefundTenderOverrideRequiredException() {
        super("refund_tender_override_required");
    }
}

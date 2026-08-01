package com.tpverp.backend.document;

/**
 * Prevents confirmation of a generic sales draft until its original
 * authorization can be proven with a persisted non-secret manifest.
 */
public final class GenericSaleConfirmationBlockedException
        extends IllegalStateException {

    public static final String CODE =
            "GENERIC_SALE_CONFIRMATION_REQUIRES_AUTHORIZATION_MANIFEST";
    public static final String MISMATCH_CODE =
            "GENERIC_SALE_AUTHORIZATION_MANIFEST_MISMATCH";

    private final Reason reason;

    public GenericSaleConfirmationBlockedException() {
        this(Reason.MISSING);
    }

    private GenericSaleConfirmationBlockedException(Reason reason) {
        super(reason == Reason.MISSING
                ? "generic_sale_confirmation_requires_persisted_authorization_manifest"
                : "generic_sale_authorization_manifest_mismatch");
        this.reason = reason;
    }

    public static GenericSaleConfirmationBlockedException mismatch() {
        return new GenericSaleConfirmationBlockedException(Reason.MISMATCH);
    }

    public String code() {
        return reason == Reason.MISSING ? CODE : MISMATCH_CODE;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MISSING,
        MISMATCH
    }
}

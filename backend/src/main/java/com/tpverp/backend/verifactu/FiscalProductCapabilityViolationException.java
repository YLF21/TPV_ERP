package com.tpverp.backend.verifactu;

/** Stable business error for operations forbidden by the installed release. */
public class FiscalProductCapabilityViolationException extends IllegalStateException {
    public static final String CODE = "FISCAL_PRODUCT_CAPABILITY_VERIFACTU_ONLY";

    public FiscalProductCapabilityViolationException(String detail) {
        super(detail);
    }
}

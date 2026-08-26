package com.tpverp.backend.verifactu;

/** A pre-V203 artifact cannot prove the taxpayer identity needed for dispatch. */
public class UnresolvedLegacyFiscalIdentityException extends IllegalStateException {
    public UnresolvedLegacyFiscalIdentityException(String detail) {
        super("IDENTIDAD_FISCAL_LEGACY_NO_RESUELTA: " + detail);
    }

    public UnresolvedLegacyFiscalIdentityException(String detail, Throwable cause) {
        super("IDENTIDAD_FISCAL_LEGACY_NO_RESUELTA: " + detail, cause);
    }
}

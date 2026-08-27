package com.tpverp.backend.verifactu;

/**
 * Blocks fiscal issuance when a due mandatory VERI*FACTU transition cannot be
 * completed atomically.
 */
public class FiscalMandatoryActivationException extends IllegalStateException {

    public FiscalMandatoryActivationException(String message) {
        super(message);
    }

    public FiscalMandatoryActivationException(String message, Throwable cause) {
        super(message, cause);
    }
}

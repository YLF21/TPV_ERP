package com.tpverp.backend.licensing.application;

public class InitialSetupException extends LicenseValidationException {
    public enum Reason { INITIAL_SETUP_NOT_EMPTY, INITIAL_ADMIN_SETUP_UNAVAILABLE }

    private final Reason reason;

    public InitialSetupException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}

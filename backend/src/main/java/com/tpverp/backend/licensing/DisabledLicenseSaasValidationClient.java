package com.tpverp.backend.licensing;

public class DisabledLicenseSaasValidationClient implements LicenseSaasValidationClient {

    @Override
    public LicenseSaasValidationResponse validate(LicenseSaasValidationRequest request) {
        throw new IllegalStateException("tpv.license.saas-url no configurado");
    }
}

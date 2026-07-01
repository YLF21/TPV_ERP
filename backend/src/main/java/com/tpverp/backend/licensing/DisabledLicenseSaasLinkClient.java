package com.tpverp.backend.licensing;

public class DisabledLicenseSaasLinkClient implements LicenseSaasLinkClient {

    @Override
    public LicenseSaasLinkResponse link(LicenseSaasLinkRequest request) {
        throw new IllegalStateException("tpv.license.saas-url no configurado");
    }
}

package com.tpverp.backend.licensing;

public class LicenseSaasValidationEndpointService {

    private final LicenseRepository licenses;

    public LicenseSaasValidationEndpointService(LicenseRepository licenses) {
        this.licenses = licenses;
    }

    public LicenseSaasValidationResponse validate(LicenseSaasValidationRequest request) {
        License license = licenses.findByReferencia(request.licenseReference())
                .orElseThrow(() -> new IllegalArgumentException("Licencia no encontrada"));
        if (!license.getInstalacionId().equals(request.installationId())
                || !license.getInstalacionReferencia().equals(request.installationReference())
                || !license.getTiendaId().equals(request.storeId())
                || !license.getHash().equalsIgnoreCase(request.licenseHash())) {
            throw new IllegalArgumentException("La licencia no pertenece a esta instalacion o tienda");
        }
        return new LicenseSaasValidationResponse(
                license.getEstadoSaas(),
                license.getValidaHasta());
    }
}

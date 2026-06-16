package com.tpverp.backend.verifactu;

public record VerifactuSystemInfo(
        String manufacturerName,
        String manufacturerTaxId,
        String systemName,
        String systemId,
        String version,
        String installationNumber,
        boolean onlyVerifactu,
        boolean multiTaxpayer,
        boolean multipleTaxpayersActive) {

    // Normaliza los datos que identifican el software dentro del XML oficial.
    public VerifactuSystemInfo {
        manufacturerName = required(manufacturerName, "manufacturerName");
        manufacturerTaxId = required(manufacturerTaxId, "manufacturerTaxId");
        systemName = required(systemName, "systemName");
        systemId = required(systemId, "systemId");
        version = required(version, "version");
        installationNumber = required(installationNumber, "installationNumber");
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }
}

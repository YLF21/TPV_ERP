package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.List;

public record FiscalIntegrityCheckView(
        Instant checkedAt,
        FiscalMode mode,
        boolean ok,
        List<String> anomalies,
        long billingRecordsChecked,
        long eventRecordsChecked,
        long anomaliesTotal,
        long billingAnomalies,
        long eventAnomalies) {

    /** Source-compatible constructor for older callers; new scans expose exact total. */
    public FiscalIntegrityCheckView(
            Instant checkedAt,
            FiscalMode mode,
            boolean ok,
            List<String> anomalies,
            long billingRecordsChecked,
            long eventRecordsChecked) {
        this(checkedAt, mode, ok, anomalies, billingRecordsChecked,
                eventRecordsChecked, anomalies == null ? 0 : anomalies.size(),
                count(anomalies, true), count(anomalies, false));
    }

    public FiscalIntegrityCheckView(
            Instant checkedAt,
            FiscalMode mode,
            boolean ok,
            List<String> anomalies,
            long billingRecordsChecked,
            long eventRecordsChecked,
            long anomaliesTotal) {
        this(checkedAt, mode, ok, anomalies, billingRecordsChecked, eventRecordsChecked,
                anomaliesTotal, count(anomalies, true), count(anomalies, false));
    }

    private static long count(List<String> anomalies, boolean billing) {
        if (anomalies == null) return 0;
        return anomalies.stream().filter(value -> value != null && (billing
                ? value.startsWith("CADENA_FACTURACION") || value.startsWith("INTEGRIDAD_SNAPSHOT")
                    || value.startsWith("INTEGRIDAD_ARTEFACTO") || value.startsWith("INTEGRIDAD_HUELLA_")
                    && !value.startsWith("INTEGRIDAD_HUELLA_EVENTO") || value.startsWith("FIRMA_REGISTRO")
                : value.startsWith("CADENA_EVENTOS") || value.startsWith("INTEGRIDAD_XML_EVENTO")
                    || value.startsWith("INTEGRIDAD_HUELLA_EVENTO") || value.startsWith("FIRMA_EVENTO"))).count();
    }
}

package com.tpverp.backend.verifactu;

/** Official event codes (L2E) for a NO VERI*FACTU SIF. */
public enum FiscalEventType {
    START_NO_VERIFACTU("01", "Inicio del funcionamiento como NO VERI*FACTU"),
    END_NO_VERIFACTU("02", "Fin del funcionamiento como NO VERI*FACTU"),
    BILLING_ANOMALY_SCAN_STARTED("03", "Lanzamiento de detección de anomalías en registros de facturación"),
    BILLING_ANOMALY_DETECTED("04", "Detección de anomalías en integridad, inalterabilidad y trazabilidad de registros de facturación"),
    EVENT_ANOMALY_SCAN_STARTED("05", "Lanzamiento de detección de anomalías en registros de evento"),
    EVENT_ANOMALY_DETECTED("06", "Detección de anomalías en integridad, inalterabilidad y trazabilidad de registros de evento"),
    BACKUP_RESTORED("07", "Restauración de copia de seguridad"),
    BILLING_EXPORT("08", "Exportación de registros de facturación generados en un periodo"),
    EVENT_EXPORT("09", "Exportación de registros de evento generados en un periodo"),
    SUMMARY("10", "Registro resumen de eventos"),
    OTHER("90", "Otros tipos de eventos registrados voluntariamente");

    private final String code;
    private final String description;

    FiscalEventType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}

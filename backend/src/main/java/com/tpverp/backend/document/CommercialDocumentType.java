package com.tpverp.backend.document;

public enum CommercialDocumentType {
    ALBARAN_VENTA("AV", Periodicidad.ANUAL),
    TICKET("T", Periodicidad.DIARIA),
    FACTURA_VENTA("FV", Periodicidad.ANUAL),
    RECTIFICATIVA_VENTA("FRV", Periodicidad.ANUAL);

    private final String prefix;
    private final Periodicidad periodicity;

    CommercialDocumentType(String prefix, Periodicidad periodicity) {
        this.prefix = prefix;
        this.periodicity = periodicity;
    }

    String prefix() {
        return prefix;
    }

    Periodicidad periodicity() {
        return periodicity;
    }

    enum Periodicidad {
        ANUAL,
        DIARIA
    }
}

package com.tpverp.backend.document;

public enum TipoDocumento {
    ALBARAN_VENTA("AV", Periodicidad.ANUAL),
    ALBARAN_COMPRA("AC", Periodicidad.ANUAL),
    TICKET("T", Periodicidad.DIARIA),
    FACTURA_VENTA("FV", Periodicidad.ANUAL),
    FACTURA_COMPRA("FC", Periodicidad.ANUAL),
    RECTIFICATIVA_VENTA("FRV", Periodicidad.ANUAL),
    RECTIFICATIVA_COMPRA("FRC", Periodicidad.ANUAL);

    private final String prefix;
    private final Periodicidad periodicity;

    TipoDocumento(String prefix, Periodicidad periodicity) {
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

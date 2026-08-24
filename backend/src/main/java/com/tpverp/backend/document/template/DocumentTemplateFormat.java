package com.tpverp.backend.document.template;

public enum DocumentTemplateFormat {
    A4,
    TICKET_80;

    public static DocumentTemplateFormat defaultFor(DocumentTemplateType type) {
        return type == DocumentTemplateType.TICKET
                || type == DocumentTemplateType.VALE
                || type == DocumentTemplateType.TICKET_REGALO
                || type == DocumentTemplateType.RETIRADA_CAJA
                ? TICKET_80 : A4;
    }

    public boolean supports(DocumentTemplateType type) {
        return switch (this) {
            case A4 -> type == DocumentTemplateType.FACTURA_VENTA
                    || type == DocumentTemplateType.ALBARAN_VENTA
                    || type == DocumentTemplateType.RECTIFICATIVA_VENTA
                    || type == DocumentTemplateType.SALIDA_ALMACEN
                    || type == DocumentTemplateType.ENTRADA_ALMACEN
                    || type == DocumentTemplateType.ALBARAN_ENTRADA
                    || type == DocumentTemplateType.FACTURA_ENTRADA
                    || type == DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO;
            case TICKET_80 -> type == DocumentTemplateType.FACTURA_VENTA
                    || type == DocumentTemplateType.TICKET
                    || type == DocumentTemplateType.VALE
                    || type == DocumentTemplateType.TICKET_REGALO
                    || type == DocumentTemplateType.RETIRADA_CAJA
                    || type == DocumentTemplateType.ALBARAN_VENTA
                    || type == DocumentTemplateType.RECTIFICATIVA_VENTA;
        };
    }
}

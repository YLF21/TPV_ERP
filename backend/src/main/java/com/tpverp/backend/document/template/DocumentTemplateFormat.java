package com.tpverp.backend.document.template;

public enum DocumentTemplateFormat {
    A4,
    TICKET_80;

    public static DocumentTemplateFormat defaultFor(DocumentTemplateType type) {
        return type == DocumentTemplateType.TICKET || type == DocumentTemplateType.VALE
                ? TICKET_80 : A4;
    }

    public boolean supports(DocumentTemplateType type) {
        return switch (this) {
            case A4 -> type == DocumentTemplateType.FACTURA_VENTA
                    || type == DocumentTemplateType.ALBARAN_VENTA;
            case TICKET_80 -> type == DocumentTemplateType.FACTURA_VENTA
                    || type == DocumentTemplateType.TICKET
                    || type == DocumentTemplateType.VALE;
        };
    }
}

package com.tpverp.backend.document.template;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Single source of truth for the document types exposed to APP GESTIÓN. */
@Component
public class DocumentTemplateDefinitionRegistry {

    private static final Map<DocumentTemplateType, Definition> DEFINITIONS = Map.ofEntries(
            entry(DocumentTemplateType.FACTURA_VENTA, "Factura de venta", "Sales invoice", "销售发票", "A4,TICKET_80", "issuer,document,lines,totals,fiscal"),
            entry(DocumentTemplateType.ALBARAN_VENTA, "Albarán de venta", "Sales delivery note", "销售送货单", "A4,TICKET_80", "issuer,document,lines"),
            entry(DocumentTemplateType.TICKET, "Ticket", "Receipt", "小票", "TICKET_80", "issuer,document,lines,totals,payment"),
            entry(DocumentTemplateType.VALE, "Vale", "Voucher", "代金券", "TICKET_80", "issuer,voucher"),
            entry(DocumentTemplateType.TICKET_REGALO, "Ticket regalo", "Gift receipt", "礼品小票", "TICKET_80", "issuer,document,lines"),
            entry(DocumentTemplateType.RETIRADA_CAJA, "Retirada de caja", "Cash withdrawal", "现金取款", "TICKET_80", "issuer,movement,lines"),
            entry(DocumentTemplateType.RECTIFICATIVA_VENTA, "Factura rectificativa", "Credit/corrective invoice", "更正发票", "A4,TICKET_80", "issuer,document,rectification,lines,totals,fiscal"),
            entry(DocumentTemplateType.SALIDA_ALMACEN, "Salida de almacén", "Warehouse output", "仓库出库", "A4", "issuer,document,warehouse,lines"),
            entry(DocumentTemplateType.ENTRADA_ALMACEN, "Entrada de almacén", "Warehouse input", "仓库入库", "A4", "issuer,document,warehouse,lines"),
            entry(DocumentTemplateType.ALBARAN_ENTRADA, "Albarán de proveedor · registro interno", "Supplier delivery note · internal record", "供应商送货单·内部记录", "A4", "issuer,document,partner,lines,internalNotice"),
            entry(DocumentTemplateType.FACTURA_ENTRADA, "Factura de proveedor · registro interno", "Supplier invoice · internal record", "供应商发票·内部记录", "A4", "issuer,document,partner,lines,internalNotice"),
            entry(DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO, "Historial de ventas de producto", "Product sales history", "产品销售历史", "A4", "issuer,history,visibleColumns"));

    public List<Definition> all() {
        return Stream.of(DocumentTemplateType.values())
                .map(DEFINITIONS::get)
                .toList();
    }

    public Definition require(DocumentTemplateType type) {
        var definition = DEFINITIONS.get(type);
        if (definition == null) {
            throw new IllegalArgumentException("document_template_definition_not_found");
        }
        return definition;
    }

    private static Map.Entry<DocumentTemplateType, Definition> entry(
            DocumentTemplateType type, String es, String en, String zh,
            String formats, String requiredFields) {
        return Map.entry(type, new Definition(
                type,
                Map.of("es", es, "en", en, "zh", zh),
                List.of(formats.split(",")),
                1,
                List.of(requiredFields.split(",")),
                true));
    }

    public record Definition(
            DocumentTemplateType type,
            Map<String, String> labels,
            List<String> formats,
            int schemaVersion,
            List<String> requiredFields,
            boolean configurable) {

        public String label(Locale locale) {
            String language = locale == null ? "es" : locale.getLanguage().toLowerCase(Locale.ROOT);
            return labels.getOrDefault(language, labels.get("es"));
        }
    }
}

package com.tpverp.backend.document.template;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Structural gate for fiscal document templates. The QR value must come from
 * the persisted fiscal snapshot; recalculating it from current configuration
 * would make historical reprints non-deterministic.
 */
final class FiscalJrxmlConformityValidator {

    private FiscalJrxmlConformityValidator() {
    }

    static void require(Map<String, byte[]> sources, DocumentTemplateType type) {
        if (type != DocumentTemplateType.TICKET
                && type != DocumentTemplateType.FACTURA_VENTA) {
            return;
        }
        var text = new StringBuilder();
        for (var source : sources.values()) {
            if (source != null) {
                text.append(new String(source, StandardCharsets.UTF_8)).append('\n');
            }
        }
        var jrxml = text.toString();
        if (!jrxml.contains("snapshot_impresion_fiscal")
                && !jrxml.contains("fiscalVerificationUrl")) {
            throw new IllegalArgumentException("document_template_fiscal_snapshot_required");
        }
        if (!jrxml.contains("qr_url") && !jrxml.contains("fiscalVerificationUrl")) {
            throw new IllegalArgumentException("document_template_fiscal_qr_url_required");
        }
        requireMarker(jrxml, "QR tributario:", "document_template_fiscal_qr_label_required");
        if (!jrxml.matches("(?s).*errorCorrectionLevel\\s*=\\s*[\\\"']M[\\\"'].*")) {
            throw new IllegalArgumentException(
                    "document_template_fiscal_qr_error_correction_required");
        }
    }

    private static void requireMarker(String source, String marker, String error) {
        if (!source.contains(marker)) {
            throw new IllegalArgumentException(error);
        }
    }
}

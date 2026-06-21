package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.SpanishTaxId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FiscalCorrectionSnapshot {

    // Copia el contenido fiscal y aplica exclusivamente cambios administrativos autorizados.
    public Map<String, Object> apply(
            Map<String, Object> original,
            FiscalCorrectionRequest request,
            UUID originalRecordId,
            UUID userId,
            Instant correctedAt,
            boolean previouslyRejected) {
        Objects.requireNonNull(original, "snapshot");
        Objects.requireNonNull(request, "request");
        var reason = required(request.reason(), "motivo de subsanacion");
        var taxId = text(request.recipientTaxId());
        var name = text(request.recipientName());
        var description = text(request.operationDescription());
        if ((taxId == null) != (name == null)) {
            throw new IllegalArgumentException(
                    "NIF y nombre del destinatario deben corregirse juntos");
        }
        if (taxId == null && description == null) {
            throw new IllegalArgumentException(
                    "La subsanacion requiere algun dato corregido");
        }
        var corrected = new LinkedHashMap<>(original);
        if (taxId != null) {
            corrected.put("cliente", correctedCustomer(original, taxId, name));
        }
        if (description != null) {
            corrected.put("descripcionOperacion", description);
        }
        corrected.put("subsanacion", "S");
        corrected.put("rechazoPrevio", previouslyRejected ? "S" : "N");
        corrected.put("subsanacionMotivo", reason);
        corrected.put("subsanacionRegistroId", Objects.requireNonNull(originalRecordId).toString());
        corrected.put("subsanacionUsuarioId", Objects.requireNonNull(userId).toString());
        corrected.put("subsanacionFecha", Objects.requireNonNull(correctedAt).toString());
        return ImmutableJson.copy(corrected);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> correctedCustomer(
            Map<String, Object> original, String taxId, String name) {
        var existing = original.get("cliente") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.<String, Object>of();
        var customer = new LinkedHashMap<>(existing);
        customer.put("numeroDocumento", SpanishTaxId.validate(taxId));
        customer.put("nombreFiscal", name);
        return customer;
    }

    private static String required(String value, String field) {
        var normalized = text(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.tpverp.backend.document;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Serializes independent stock-output confirmations by store and normalized S/N.
 * PostgreSQL transaction advisory locks are deliberately acquired in lexical order
 * so confirmations containing several serials cannot deadlock each other.
 */
final class DocumentSerialNumberGuard {

    private static final EnumSet<CommercialDocumentType> GUARDED_TYPES = EnumSet.of(
            CommercialDocumentType.TICKET,
            CommercialDocumentType.ALBARAN_VENTA,
            CommercialDocumentType.FACTURA_VENTA);

    private final CommercialDocumentRepository documents;

    DocumentSerialNumberGuard(CommercialDocumentRepository documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    void lockAndValidate(CommercialDocument document, boolean appliesStock) {
        Objects.requireNonNull(document, "document");
        if (!appliesStock || !GUARDED_TYPES.contains(document.getTipo())) {
            return;
        }
        var normalized = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .filter(line -> line.getCantidad().signum() > 0)
                .flatMap(line -> line.getSerialNumbers().stream())
                .map(DocumentSerialNumberGuard::normalize)
                .filter(value -> !value.isBlank())
                .toList();
        if (normalized.isEmpty()) {
            return;
        }
        var ordered = normalized.stream().distinct().sorted().toList();
        if (ordered.size() != normalized.size()) {
            throw new IllegalArgumentException(
                    "message.document.serial_number_duplicated");
        }
        ordered.forEach(serial -> documents.lockSerialNumber(lockKey(document, serial)));
        if (!documents.usedSerialNumbers(document.getTiendaId(), ordered).isEmpty()) {
            throw new IllegalArgumentException(
                    "message.document.serial_number_already_used");
        }
    }

    static String lockKey(CommercialDocument document, String normalizedSerial) {
        return "document-sale-serial|" + document.getTiendaId()
                + "|" + normalizedSerial.length() + ":" + normalizedSerial;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

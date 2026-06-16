package com.tpverp.backend.document;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class DocumentNumbering {

    private static final DateTimeFormatter TICKET_DATE =
            DateTimeFormatter.ofPattern("yyMMdd");

    private DocumentNumbering() {
    }

    public static String format(TipoDocumento type, LocalDate date, int sequence) {
        return format(type, date, sequence, "001");
    }

    // Formatea el consecutivo incluyendo el codigo fiscal de tienda.
    public static String format(
            TipoDocumento type, LocalDate date, int sequence, String storeCode) {
        Objects.requireNonNull(type, "tipo");
        Objects.requireNonNull(date, "fecha");
        storeCode = normalizeStoreCode(storeCode);
        if (sequence < 1 || sequence > 999_999) {
            throw new IllegalArgumentException("secuencia fuera de rango");
        }
        if (type == TipoDocumento.TICKET) {
            if (sequence > 99_999) {
                throw new IllegalArgumentException("secuencia diaria de ticket fuera de rango");
            }
            return "%s-%s-%05d".formatted(storeCode, TICKET_DATE.format(date), sequence);
        }
        return "%s-%s-%ty-%06d".formatted(type.prefix(), storeCode, date, sequence);
    }

    public static String period(TipoDocumento type, LocalDate date) {
        Objects.requireNonNull(type, "tipo");
        Objects.requireNonNull(date, "fecha");
        return type.periodicity() == TipoDocumento.Periodicidad.DIARIA
                ? date.format(DateTimeFormatter.BASIC_ISO_DATE)
                : Integer.toString(date.getYear());
    }

    private static String normalizeStoreCode(String storeCode) {
        var normalized = storeCode == null ? "" : storeCode.trim();
        if (!normalized.matches("[0-9]{3}") || normalized.equals("000")) {
            throw new IllegalArgumentException("codigoTienda fiscal no valido");
        }
        return normalized;
    }
}

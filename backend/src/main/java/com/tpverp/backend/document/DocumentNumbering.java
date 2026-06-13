package com.tpverp.backend.document;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class DocumentNumbering {

    private static final DateTimeFormatter TICKET_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private DocumentNumbering() {
    }

    // Formatea el consecutivo según la periodicidad fiscal del tipo documental.
    public static String format(TipoDocumento type, LocalDate date, int sequence) {
        Objects.requireNonNull(type, "tipo");
        Objects.requireNonNull(date, "fecha");
        if (sequence < 1 || sequence > 999_999) {
            throw new IllegalArgumentException("secuencia fuera de rango");
        }
        if (type == TipoDocumento.TICKET) {
            if (sequence > 99_999) {
                throw new IllegalArgumentException("secuencia diaria de ticket fuera de rango");
            }
            return TICKET_DATE.format(date) + "%05d".formatted(sequence);
        }
        return "%s-%d-%06d".formatted(type.prefix(), date.getYear(), sequence);
    }

    // Obtiene el periodo persistido por el contador anual o diario.
    public static String period(TipoDocumento type, LocalDate date) {
        Objects.requireNonNull(type, "tipo");
        Objects.requireNonNull(date, "fecha");
        return type.periodicity() == TipoDocumento.Periodicidad.DIARIA
                ? date.format(DateTimeFormatter.BASIC_ISO_DATE)
                : Integer.toString(date.getYear());
    }
}

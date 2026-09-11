package com.tpverp.backend.party;

import com.tpverp.backend.organization.SpanishTaxId;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical customer identity shared by validation and company-wide duplicate checks. */
public record CustomerDocumentIdentity(DocumentType canonicalType, String canonicalNumber) {

    private static final Pattern DNI = Pattern.compile("[0-9]{8}[A-Z]");
    private static final Pattern NIE = Pattern.compile("[XYZ][0-9]{7}[A-Z]");

    public CustomerDocumentIdentity {
        canonicalType = canonicalType(canonicalType);
        canonicalNumber = normalizeNumber(canonicalNumber);
        if (canonicalType == DocumentType.DNI && !DNI.matcher(canonicalNumber).matches()) {
            throw new IllegalArgumentException("El DNI debe contener ocho números y una letra de control");
        }
        if (canonicalType == DocumentType.NIE && !NIE.matcher(canonicalNumber).matches()) {
            throw new IllegalArgumentException("El NIE debe contener X, Y o Z, siete números y una letra de control");
        }
        if (canonicalType != DocumentType.PASAPORTE) {
            SpanishTaxId.validate(canonicalNumber);
        }
    }

    public static CustomerDocumentIdentity validate(DocumentType type, String number) {
        return new CustomerDocumentIdentity(type, number);
    }

    public static DocumentType canonicalType(DocumentType type) {
        if (type == null) {
            throw new IllegalArgumentException("El tipo de documento es obligatorio");
        }
        return switch (type) {
            case CIF -> DocumentType.NIF;
            case OTRO -> DocumentType.PASAPORTE;
            default -> type;
        };
    }

    /** Must stay aligned with the SaaS identity key: ASCII spaces and hyphens only. */
    public static String normalizeNumber(String number) {
        if (number == null) {
            throw new IllegalArgumentException("El número de documento es obligatorio");
        }
        var normalized = number.trim().replace(" ", "").replace("-", "")
                .toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("El número de documento es obligatorio");
        }
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("El número de documento admite un máximo de 64 caracteres");
        }
        return normalized;
    }
}

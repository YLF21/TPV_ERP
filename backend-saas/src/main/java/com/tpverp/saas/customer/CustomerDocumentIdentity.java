package com.tpverp.saas.customer;

import com.tpverp.saas.license.SpanishTaxId;
import java.util.Locale;

public record CustomerDocumentIdentity(String documentType, String documentNumber) {
    public static CustomerDocumentIdentity validate(String rawType, String rawNumber) {
        String type = rawType == null ? "NIF" : rawType.trim().toUpperCase(Locale.ROOT);
        type = switch (type) {
            case "CIF" -> "NIF";
            case "OTRO" -> "PASAPORTE";
            default -> type;
        };
        String number = normalize(rawNumber);
        if (number.isEmpty() || number.length() > 64) throw CustomerIdentityException.invalid();
        if (type.equals("PASAPORTE")) return new CustomerDocumentIdentity(type, number);
        if (!(type.equals("NIE") || type.equals("DNI") || type.equals("NIF"))) {
            throw CustomerIdentityException.invalid();
        }
        if (type.equals("DNI") && !number.matches("[0-9]{8}[A-Z]")) throw CustomerIdentityException.invalid();
        if (type.equals("NIE") && !number.matches("[XYZ][0-9]{7}[A-Z]")) throw CustomerIdentityException.invalid();
        try {
            SpanishTaxId.validate(number);
        } catch (IllegalArgumentException exception) {
            throw CustomerIdentityException.invalid();
        }
        return new CustomerDocumentIdentity(type, number);
    }

    /** Keep this key normalization identical to the local ERP and the V53 SQL function. */
    public static String normalize(String value) {
        return value == null ? "" : value.trim().replace(" ", "").replace("-", "")
                .toUpperCase(Locale.ROOT);
    }
}

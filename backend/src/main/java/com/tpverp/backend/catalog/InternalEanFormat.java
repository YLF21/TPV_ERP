package com.tpverp.backend.catalog;

import java.util.Locale;

public enum InternalEanFormat {
    EAN_8(8, 999),
    EAN_13(13, 999999);

    private final int length;
    private final int maximumSequence;

    InternalEanFormat(int length, int maximumSequence) {
        this.length = length;
        this.maximumSequence = maximumSequence;
    }

    public int length() {
        return length;
    }

    public int maximumSequence() {
        return maximumSequence;
    }

    public String compose(String companyCode, String storeCode, long sequence) {
        if (sequence < 0 || sequence > maximumSequence) {
            throw new IllegalArgumentException("internal_ean_sequence_exhausted");
        }
        var normalizedStore = digits(storeCode, 3, "internal_ean_store_code_invalid");
        var body = switch (this) {
            case EAN_8 -> "2" + normalizedStore + String.format(Locale.ROOT, "%03d", sequence);
            case EAN_13 -> "2" + digits(companyCode, 2, "internal_ean_company_code_invalid")
                    + normalizedStore + String.format(Locale.ROOT, "%06d", sequence);
        };
        return body + checkDigit(body);
    }

    public static InternalEanFormat fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("internal_ean_format_required");
        }
        return switch (code.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "EAN8", "EAN_8" -> EAN_8;
            case "EAN13", "EAN_13" -> EAN_13;
            default -> throw new IllegalArgumentException("internal_ean_format_invalid");
        };
    }

    public static Validation validate(String rawCode) {
        var code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("[0-9]+")) {
            return new Validation(code, null, false, "NON_NUMERIC");
        }
        InternalEanFormat format;
        if (code.length() == 8) {
            format = EAN_8;
        } else if (code.length() == 13) {
            format = EAN_13;
        } else {
            return new Validation(code, null, false, "INVALID_LENGTH");
        }
        var body = code.substring(0, code.length() - 1);
        var valid = code.charAt(code.length() - 1) - '0' == checkDigit(body);
        return new Validation(code, format, valid, valid ? null : "INVALID_CHECK_DIGIT");
    }

    public static int checkDigit(String body) {
        if (body == null || !body.matches("[0-9]+")) {
            throw new IllegalArgumentException("internal_ean_body_invalid");
        }
        var sum = 0;
        var weightThree = true;
        for (var index = body.length() - 1; index >= 0; index--) {
            var digit = body.charAt(index) - '0';
            sum += digit * (weightThree ? 3 : 1);
            weightThree = !weightThree;
        }
        return (10 - (sum % 10)) % 10;
    }

    private static String digits(String value, int length, String error) {
        var normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[0-9]{" + length + "}")) {
            throw new IllegalArgumentException(error);
        }
        return normalized;
    }

    public record Validation(
            String code,
            InternalEanFormat format,
            boolean valid,
            String reason) {
    }
}

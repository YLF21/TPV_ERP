package com.tpverp.bridge.app;

import com.tpverp.bridge.spi.OperationResult;
import java.util.Set;
import java.util.regex.Pattern;

final class SafeResultPolicy {
    private static final Set<String> SUCCESS_CODES = Set.of(
            "APPROVED", "VOIDED", "REFUNDED", "PARTIALLY_REFUNDED", "PAIRED", "RECEIPT_AVAILABLE", "RECONCILED");
    private static final Set<String> COMMON_FAILURE_CODES = Set.of(
            "DECLINED", "CANCELLED", "PENDING", "TIMEOUT", "ERROR", "REVIEW_REQUIRED", "SDK_NOT_INSTALLED",
            "INVALID_RESPONSE");
    private static final Pattern CARD_NUMBER = Pattern.compile("(?<!\\d)(\\d{9,15})(\\d{4})(?!\\d)");

    private SafeResultPolicy() {
    }

    static OperationResult normalize(String command, OperationResult result) {
        if (result == null || result.code() == null || !result.code().matches("[A-Z0-9_]{1,64}")) return invalid();
        var code = result.code();
        if (!allowed(command, code) || result.approved() != SUCCESS_CODES.contains(code)) return invalid();
        var reference = safe(result.reference(), 256, false);
        var authorization = safe(result.authorization(), 64, false);
        var message = safe(result.message(), 512, false);
        var receipt = safe(result.receiptText(), 4_000, true);
        if (result.approved() && !"RECEIPT_AVAILABLE".equals(code) && reference == null) return invalid();
        if ("RECEIPT_AVAILABLE".equals(code) && (receipt == null || receipt.isBlank())) return invalid();
        return new OperationResult(result.approved(), code, reference, authorization, message, receipt);
    }

    static OperationResult invalid() {
        return OperationResult.failure("INVALID_RESPONSE", "Respuesta no válida del adaptador");
    }

    private static boolean allowed(String command, String code) {
        if (COMMON_FAILURE_CODES.contains(code)) return true;
        return switch (command) {
            case "PAIR", "PAIRING_STATUS" -> Set.of("PAIRED", "PAIRING_NOT_FOUND").contains(code);
            case "CHARGE" -> "APPROVED".equals(code);
            case "QUERY" -> Set.of("APPROVED", "VOIDED", "REFUNDED", "PARTIALLY_REFUNDED", "OPERATION_NOT_FOUND").contains(code);
            case "VOID" -> Set.of("VOIDED", "OPERATION_NOT_FOUND").contains(code);
            case "REFUND" -> Set.of("REFUNDED", "PARTIALLY_REFUNDED", "OPERATION_NOT_FOUND").contains(code);
            case "RECEIPT" -> Set.of("RECEIPT_AVAILABLE", "OPERATION_NOT_FOUND").contains(code);
            case "RECONCILIATION" -> "RECONCILED".equals(code);
            default -> false;
        };
    }

    private static String safe(String value, int maximum, boolean multiline) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > maximum || value.chars().anyMatch(character -> Character.isISOControl(character)
                && (!multiline || character != '\n' && character != '\r' && character != '\t'))) return null;
        var matcher = CARD_NUMBER.matcher(value.trim());
        var output = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(output, "****" + matcher.group(2));
        matcher.appendTail(output);
        return output.toString();
    }
}

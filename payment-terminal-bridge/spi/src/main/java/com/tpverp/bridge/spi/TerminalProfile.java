package com.tpverp.bridge.spi;

import java.util.Locale;
import java.util.Map;

public record TerminalProfile(
        String terminalId,
        String provider,
        String adapterId,
        TerminalExecutionMode mode,
        String model,
        String connectionType,
        String secretReference,
        Map<String, String> parameters) {

    public TerminalProfile {
        terminalId = identifier(terminalId, "terminalId", 64);
        provider = identifier(provider, "provider", 32).toUpperCase(Locale.ROOT);
        adapterId = identifier(adapterId, "adapterId", 64).toLowerCase(Locale.ROOT);
        if (mode == null) throw new IllegalArgumentException("mode");
        model = text(model, "model", 128);
        connectionType = identifier(connectionType, "connectionType", 32).toUpperCase(Locale.ROOT);
        secretReference = optionalIdentifier(secretReference, "secretReference", 128);
        parameters = safeParameters(parameters);
    }

    private static Map<String, String> safeParameters(Map<String, String> input) {
        var source = input == null ? Map.<String, String>of() : input;
        if (source.size() > 32) throw new IllegalArgumentException("Too many terminal parameters");
        source.forEach((key, value) -> {
            text(key, "parameter key", 64);
            text(value, "parameter value", 256);
            if (SensitiveParameterName.isSensitive(key)) {
                throw new IllegalArgumentException("Sensitive terminal parameter is not allowed");
            }
        });
        return Map.copyOf(source);
    }

    private static String identifier(String value, String field, int maximum) {
        var result = text(value, field, maximum);
        if (!result.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException(field);
        return result;
    }

    private static String optionalIdentifier(String value, String field, int maximum) {
        if (value == null || value.isBlank()) return null;
        return identifier(value, field, maximum);
    }

    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
        return value.trim();
    }
}

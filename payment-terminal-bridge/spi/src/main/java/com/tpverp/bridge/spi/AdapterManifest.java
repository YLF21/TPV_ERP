package com.tpverp.bridge.spi;

import java.util.Locale;
import java.util.Set;

/** Sanitized metadata used to configure and audit an installed adapter. */
public record AdapterManifest(
        String adapterId,
        String provider,
        String displayName,
        Set<TerminalExecutionMode> modes,
        Set<String> protocols,
        Set<String> connectionTypes,
        boolean certifiedLiveDriverInstalled) {

    public AdapterManifest {
        adapterId = identifier(adapterId, "adapterId", 64).toLowerCase(Locale.ROOT);
        provider = identifier(provider, "provider", 32).toUpperCase(Locale.ROOT);
        displayName = text(displayName, "displayName", 128);
        modes = modes == null ? Set.of() : Set.copyOf(modes);
        protocols = identifiers(protocols, "protocol", 64);
        connectionTypes = identifiers(connectionTypes, "connectionType", 32);
        if (certifiedLiveDriverInstalled && !modes.contains(TerminalExecutionMode.LIVE)) {
            throw new IllegalArgumentException("A certified LIVE driver must advertise LIVE mode");
        }
    }

    public static AdapterManifest unavailable(String adapterId, String provider) {
        return new AdapterManifest(adapterId, provider, provider, Set.of(), Set.of(), Set.of(), false);
    }

    private static Set<String> identifiers(Set<String> source, String field, int maximum) {
        if (source == null) return Set.of();
        if (source.size() > 64) throw new IllegalArgumentException("Too many " + field + " values");
        return source.stream().map(value -> identifier(value, field, maximum).toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String identifier(String value, String field, int maximum) {
        var result = text(value, field, maximum);
        if (!result.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException(field);
        return result;
    }

    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field);
        return value.trim();
    }
}

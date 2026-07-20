package com.tpverp.bridge.app;

import com.tpverp.bridge.spi.TerminalProfile;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class TerminalProfileRegistry {
    private final Map<String, TerminalProfile> profiles;

    public TerminalProfileRegistry(List<TerminalProfile> profiles) {
        this.profiles = List.copyOf(profiles).stream()
                .collect(Collectors.toUnmodifiableMap(TerminalProfile::terminalId, Function.identity()));
    }

    public TerminalProfile required(String terminalId, String provider, com.tpverp.bridge.spi.TerminalExecutionMode mode) {
        if (terminalId == null || provider == null || mode == null) throw new IllegalArgumentException("Terminal, provider and mode are required");
        var profile = profiles.get(terminalId);
        if (profile == null || !profile.provider().equals(provider.toUpperCase(Locale.ROOT)) || profile.mode() != mode) {
            throw new IllegalArgumentException("Terminal profile not found");
        }
        return profile;
    }

    public List<TerminalProfile> forProvider(String provider, com.tpverp.bridge.spi.TerminalExecutionMode mode) {
        var normalized = provider == null || provider.isBlank() ? null : provider.toUpperCase(Locale.ROOT);
        return profiles.values().stream()
                .filter(profile -> normalized == null || profile.provider().equals(normalized))
                .filter(profile -> mode == null || profile.mode() == mode)
                .toList();
    }
}

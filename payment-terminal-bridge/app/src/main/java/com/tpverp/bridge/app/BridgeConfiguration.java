package com.tpverp.bridge.app;

import com.tpverp.bridge.spi.TerminalProfile;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record BridgeConfiguration(
        int version,
        String listenHost,
        int port,
        String authenticationTokenReference,
        String dataDirectory,
        String pluginsDirectory,
        Map<String, String> pluginDigests,
        List<TerminalProfile> terminals) {

    public BridgeConfiguration {
        if (version != 1) throw new IllegalArgumentException("Unsupported bridge configuration version");
        listenHost = requireLoopback(listenHost);
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port");
        authenticationTokenReference = requiredReference(authenticationTokenReference);
        dataDirectory = required(dataDirectory, "dataDirectory", 512);
        pluginsDirectory = required(pluginsDirectory, "pluginsDirectory", 512);
        pluginDigests = validateDigests(pluginDigests);
        terminals = terminals == null ? List.of() : List.copyOf(terminals);
        var ids = new HashSet<String>();
        for (var terminal : terminals) {
            if (!ids.add(terminal.terminalId())) throw new IllegalArgumentException("Duplicate terminalId");
        }
    }

    public Path dataPath(Path baseDirectory) {
        return resolve(baseDirectory, dataDirectory);
    }

    public Path pluginsPath(Path baseDirectory) {
        return resolve(baseDirectory, pluginsDirectory);
    }

    private static Path resolve(Path baseDirectory, String value) {
        var path = Path.of(value);
        return (path.isAbsolute() ? path : baseDirectory.resolve(path)).normalize().toAbsolutePath();
    }

    private static String requireLoopback(String value) {
        var host = required(value, "listenHost", 128);
        try {
            var addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0 || java.util.Arrays.stream(addresses).anyMatch(address -> !address.isLoopbackAddress())) {
                throw new IllegalArgumentException("Bridge must listen only on loopback");
            }
            return host;
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("listenHost", exception);
        }
    }

    private static Map<String, String> validateDigests(Map<String, String> values) {
        var source = values == null ? Map.<String, String>of() : values;
        if (source.size() > 32) throw new IllegalArgumentException("Too many plugins");
        source.forEach((name, digest) -> {
            if (name == null || !name.matches("[A-Za-z0-9._-]+\\.jar")) throw new IllegalArgumentException("Plugin filename");
            if (digest == null || !digest.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Plugin digest");
            }
        });
        return Map.copyOf(source);
    }

    private static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
        return value.trim();
    }

    private static String requiredReference(String value) {
        var reference = required(value, "authenticationTokenReference", 128);
        if (!reference.matches("(?:windows|local):[A-Za-z0-9._:-]{1,112}")) {
            throw new IllegalArgumentException("authenticationTokenReference");
        }
        return reference;
    }
}

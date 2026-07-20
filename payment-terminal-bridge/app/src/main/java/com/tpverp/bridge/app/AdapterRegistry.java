package com.tpverp.bridge.app;

import com.tpverp.bridge.spi.PaymentTerminalAdapter;
import com.tpverp.bridge.spi.TerminalProfile;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

public final class AdapterRegistry implements AutoCloseable {
    private final Map<String, PaymentTerminalAdapter> adapters;
    private final URLClassLoader pluginClassLoader;

    private AdapterRegistry(List<PaymentTerminalAdapter> values, URLClassLoader pluginClassLoader,
            com.tpverp.bridge.spi.AdapterRuntime runtime) {
        var indexed = new LinkedHashMap<String, PaymentTerminalAdapter>();
        for (var adapter : values) {
            var id = normalized(adapter.adapterId());
            var provider = provider(adapter.provider());
            if (indexed.putIfAbsent(id, adapter) != null) throw new IllegalArgumentException("Duplicate adapterId: " + id);
            if (provider.isBlank()) throw new IllegalArgumentException("Adapter provider is required");
        }
        this.adapters = Map.copyOf(indexed);
        this.pluginClassLoader = pluginClassLoader;
        this.adapters.values().forEach(adapter -> adapter.initialize(runtime));
    }

    public static AdapterRegistry of(PaymentTerminalAdapter... adapters) {
        return new AdapterRegistry(List.of(adapters), null, com.tpverp.bridge.spi.AdapterRuntime.unavailable());
    }

    public static AdapterRegistry load(Path directory, Map<String, String> expectedDigests) throws IOException {
        return load(directory, expectedDigests, com.tpverp.bridge.spi.AdapterRuntime.unavailable());
    }

    public static AdapterRegistry load(Path directory, Map<String, String> expectedDigests,
            com.tpverp.bridge.spi.AdapterRuntime runtime) throws IOException {
        Files.createDirectories(directory);
        final List<Path> jars;
        try (var paths = Files.list(directory)) {
            jars = paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList();
        }
        var actualNames = jars.stream().map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        if (!actualNames.equals(expectedDigests.keySet())) {
            throw new SecurityException("Plugin directory does not match the configured allow-list");
        }
        for (var jar : jars) {
            var expected = expectedDigests.get(jar.getFileName().toString()).toLowerCase(Locale.ROOT);
            if (!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII), sha256(jar).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new SecurityException("Plugin digest mismatch: " + jar.getFileName());
            }
        }
        var loader = new URLClassLoader(jars.stream().map(AdapterRegistry::url).toArray(java.net.URL[]::new),
                PaymentTerminalAdapter.class.getClassLoader());
        var loaded = new ArrayList<PaymentTerminalAdapter>();
        ServiceLoader.load(PaymentTerminalAdapter.class, loader).forEach(loaded::add);
        return new AdapterRegistry(loaded, loader, runtime);
    }

    public PaymentTerminalAdapter required(TerminalProfile profile) {
        var adapter = adapters.get(normalized(profile.adapterId()));
        if (adapter == null || !provider(adapter.provider()).equals(profile.provider())) {
            throw new AdapterNotInstalledException(profile.adapterId());
        }
        var manifest = adapter.manifest();
        if (!normalized(manifest.adapterId()).equals(normalized(adapter.adapterId()))
                || !provider(manifest.provider()).equals(provider(adapter.provider()))
                || !manifest.modes().contains(profile.mode())
                || (profile.mode() == com.tpverp.bridge.spi.TerminalExecutionMode.LIVE
                    && !manifest.certifiedLiveDriverInstalled())
                || !adapter.supports(profile)) {
            throw new AdapterNotInstalledException(profile.adapterId());
        }
        return adapter;
    }

    public List<com.tpverp.bridge.spi.AdapterManifest> manifests() {
        return adapters.values().stream().map(PaymentTerminalAdapter::manifest)
                .sorted(java.util.Comparator.comparing(com.tpverp.bridge.spi.AdapterManifest::adapterId)).toList();
    }

    public boolean isInstalled(TerminalProfile profile) {
        try {
            required(profile);
            return true;
        } catch (AdapterNotInstalledException exception) {
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (var adapter : adapters.values()) {
            try {
                adapter.close();
            } catch (Exception exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        if (pluginClassLoader != null) pluginClassLoader.close();
        if (failure != null) throw failure;
    }

    private static String normalized(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("adapterId");
        return value.toLowerCase(Locale.ROOT);
    }

    private static String provider(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,32}")) throw new IllegalArgumentException("provider");
        return value.toUpperCase(Locale.ROOT);
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static java.net.URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    public static final class AdapterNotInstalledException extends RuntimeException {
        public AdapterNotInstalledException(String adapterId) {
            super("Adapter not installed: " + adapterId);
        }
    }
}

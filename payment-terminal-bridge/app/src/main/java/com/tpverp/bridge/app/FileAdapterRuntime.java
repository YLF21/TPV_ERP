package com.tpverp.bridge.app;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import com.tpverp.bridge.spi.AdapterRuntime;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/** Machine-protected secret vault and bounded durable state for adapter drivers. */
public final class FileAdapterRuntime implements AdapterRuntime {
    private static final int MAXIMUM_STATE_BYTES = 1024 * 1024;
    private static final int DPAPI_FLAGS = WinCrypt.CRYPTPROTECT_LOCAL_MACHINE | WinCrypt.CRYPTPROTECT_UI_FORBIDDEN;
    private final Path root;

    public FileAdapterRuntime(Path dataDirectory) throws IOException {
        root = dataDirectory.resolve("adapter-runtime").normalize().toAbsolutePath();
        Files.createDirectories(root.resolve("state"));
        Files.createDirectories(root.resolve("secrets"));
    }

    @Override
    public <T> T withSecret(String reference, SecretUse<T> use) {
        requiredReference(reference);
        if (use == null) throw new IllegalArgumentException("Secret callback is required");
        if (!isWindows()) throw new IllegalStateException("DPAPI secret vault requires Windows");
        byte[] protectedValue = null;
        byte[] plaintext = null;
        try {
            protectedValue = Files.readAllBytes(secretPath(reference));
            plaintext = Crypt32Util.cryptUnprotectData(protectedValue, DPAPI_FLAGS);
            return use.apply(plaintext);
        } catch (IOException exception) {
            throw new IllegalStateException("Secret reference is unavailable", exception);
        } finally {
            wipe(protectedValue);
            wipe(plaintext);
        }
    }

    public void putSecret(String reference, byte[] value) {
        requiredReference(reference);
        if (!isWindows()) throw new IllegalStateException("DPAPI secret vault requires Windows");
        if (value == null || value.length == 0 || value.length > 64 * 1024) throw new IllegalArgumentException("secret");
        byte[] plaintext = value.clone();
        byte[] protectedValue = null;
        try {
            protectedValue = Crypt32Util.cryptProtectData(plaintext, DPAPI_FLAGS);
            atomicWrite(secretPath(reference), protectedValue);
        } finally {
            wipe(plaintext);
            wipe(protectedValue);
        }
    }

    public void deleteSecret(String reference) {
        requiredReference(reference);
        var path = secretPath(reference);
        try {
            if (!Files.exists(path)) return;
            var size = Files.size(path);
            if (size > 0 && size <= 1024 * 1024) Files.write(path, new byte[(int) size]);
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Secret could not be deleted", exception);
        }
    }

    @Override
    public Optional<byte[]> readState(String namespace, String key) {
        var path = statePath(namespace, key);
        try {
            if (!Files.isRegularFile(path)) return Optional.empty();
            if (Files.size(path) > MAXIMUM_STATE_BYTES) throw new IllegalStateException("Adapter state is too large");
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Adapter state is unavailable", exception);
        }
    }

    @Override
    public void writeState(String namespace, String key, byte[] value) {
        if (value == null || value.length == 0 || value.length > MAXIMUM_STATE_BYTES) {
            throw new IllegalArgumentException("Adapter state size");
        }
        atomicWrite(statePath(namespace, key), value.clone());
    }

    @Override
    public void deleteState(String namespace, String key) {
        try {
            Files.deleteIfExists(statePath(namespace, key));
        } catch (IOException exception) {
            throw new IllegalStateException("Adapter state could not be deleted", exception);
        }
    }

    private Path statePath(String namespace, String key) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("namespace");
        if (key == null || key.isBlank() || key.length() > 512 || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("state key");
        }
        return root.resolve("state").resolve(namespace).resolve(hash(key) + ".state");
    }

    private Path secretPath(String reference) {
        return root.resolve("secrets").resolve(hash(requiredReference(reference)) + ".dpapi");
    }

    private static String requiredReference(String reference) {
        if (reference == null || !reference.matches("(?:windows|local):[A-Za-z0-9._:-]{1,112}")) {
            throw new IllegalArgumentException("secretReference");
        }
        return reference;
    }

    private static void atomicWrite(Path target, byte[] value) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".tpv-", ".tmp");
            Files.write(temporary, value);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Adapter runtime write failed", exception);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            wipe(value);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }
}

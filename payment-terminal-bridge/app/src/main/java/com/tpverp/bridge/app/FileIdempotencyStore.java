package com.tpverp.bridge.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.bridge.spi.OperationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

public final class FileIdempotencyStore {
    private static final long MAXIMUM_ENTRY_BYTES = 32 * 1024L;
    private static final int LOCK_STRIPES = 256;
    private final Path directory;
    private final ObjectMapper mapper;
    private final Object[] locks = new Object[LOCK_STRIPES];

    public FileIdempotencyStore(Path dataDirectory, ObjectMapper mapper) throws IOException {
        this.directory = dataDirectory.resolve("idempotency").normalize().toAbsolutePath();
        this.mapper = mapper;
        Files.createDirectories(directory);
        java.util.Arrays.setAll(locks, ignored -> new Object());
    }

    public OperationResult execute(String scope, String idempotencyKey, String fingerprint,
            Supplier<OperationResult> operation) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            return OperationResult.failure("ERROR", "Clave de idempotencia no válida");
        }
        var storageKey = sha256(scope + "\u0000" + idempotencyKey);
        var lock = locks[Math.floorMod(storageKey.hashCode(), locks.length)];
        synchronized (lock) {
            return executeLocked(storageKey, fingerprint, operation);
        }
    }

    private OperationResult executeLocked(String storageKey, String fingerprint, Supplier<OperationResult> operation) {
        var path = directory.resolve(storageKey + ".json");
        if (Files.exists(path)) {
            try {
                var entry = read(path);
                if (!MessageDigest.isEqual(entry.fingerprint().getBytes(StandardCharsets.US_ASCII),
                        fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                    return OperationResult.failure("REVIEW_REQUIRED", "La clave de idempotencia ya pertenece a otra operación");
                }
                if (entry.state() == State.COMPLETED && entry.result() != null) return entry.result();
                return OperationResult.failure("REVIEW_REQUIRED", "Operación con estado incierto; consulte antes de repetir");
            } catch (IOException | RuntimeException exception) {
                return OperationResult.failure("REVIEW_REQUIRED", "No se puede verificar el diario de la operación");
            }
        }
        try {
            write(path, new Entry(fingerprint, State.IN_PROGRESS, null));
        } catch (IOException exception) {
            return OperationResult.failure("ERROR", "No se puede proteger la operación contra duplicados");
        }
        final OperationResult result;
        try {
            result = operation.get();
        } catch (RuntimeException exception) {
            return OperationResult.failure("REVIEW_REQUIRED", "El adaptador falló con resultado desconocido; consulte la operación");
        }
        try {
            write(path, new Entry(fingerprint, State.COMPLETED, result));
            return result;
        } catch (IOException exception) {
            return OperationResult.failure("REVIEW_REQUIRED", "Resultado obtenido pero no confirmado en el diario; consulte la operación");
        }
    }

    private Entry read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAXIMUM_ENTRY_BYTES) throw new IOException("Invalid journal entry");
        return mapper.readValue(path.toFile(), Entry.class);
    }

    private void write(Path target, Entry entry) throws IOException {
        var temporary = Files.createTempFile(directory, "entry-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), entry);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private enum State { IN_PROGRESS, COMPLETED }
    private record Entry(String fingerprint, State state, OperationResult result) { }
}

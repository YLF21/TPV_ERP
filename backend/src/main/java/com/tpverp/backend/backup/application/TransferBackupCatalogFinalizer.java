package com.tpverp.backend.backup.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;

/** Repairs only the final backup's operational catalogue, never fiscal or synchronization state. */
public final class TransferBackupCatalogFinalizer {
    private TransferBackupCatalogFinalizer() { }

    public static void finalizeReceived(Connection db, JsonNode manifest, Path backup, Path directory) throws Exception {
        finalizeReceived(db, manifest, backup, directory, digest(backup));
    }

    public static void finalizeReceived(Connection db, JsonNode manifest, Path backup, Path directory, String expectedSha256) throws Exception {
        UUID execution = UUID.fromString(manifest.path("executionId").asText());
        long sequence = manifest.path("sequenceNumber").asLong();
        if (sequence < 1 || !"TRANSFER".equals(manifest.path("origin").asText()) || db.getAutoCommit()
                || expectedSha256 == null || !expectedSha256.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("El catálogo requiere una copia de traslado y una transacción de mantenimiento");
        }
        Path destination = directory.toAbsolutePath().normalize();
        requireSafePath(destination);
        Path daily = destination.resolve("daily");
        Files.createDirectories(daily);
        requireSafePath(daily);
        Path target = daily.resolve("%06d.tpvb".formatted(sequence));
        boolean present = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (present) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !expectedSha256.equalsIgnoreCase(digest(target))) {
                throw new IllegalStateException("Ya existe otra copia con el mismo ID en el destino");
            }
        } else {
            Path temporary = Files.createTempFile(daily, ".tpv-transfer-", ".tmp");
            boolean reserved = false;
            boolean promoted = false;
            try {
                Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!expectedSha256.equalsIgnoreCase(digest(temporary))) throw new IllegalStateException("La copia cambió durante el traslado");
                Files.createFile(target);
                reserved = true;
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                promoted = true;
            } finally {
                Files.deleteIfExists(temporary);
                if (reserved && !promoted) Files.deleteIfExists(target);
            }
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("path", target.toString());
        metadata.put("sizeBytes", Files.size(target));
        metadata.put("manifest", manifest);
        metadata.put("verifiedAt", java.time.Instant.now().toString());
        metadata.put("cloudAlreadyStored", true);
        var mapper = new ObjectMapper();
        try (var statement = db.prepareStatement("""
                update ejecucion_backup set result='EXITO', phase='COMPLETED',
                    finalizada_en=greatest(iniciada_en,now()), lease_until=greatest(iniciada_en,now()),
                    heartbeat_at=now(), metadata=cast(? as jsonb), error_reason=null
                where id=? and sequence_number=? and origin='TRANSFER'
                """)) {
            statement.setString(1, mapper.writeValueAsString(metadata));
            statement.setObject(2, execution);
            statement.setLong(3, sequence);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("La copia final no coincide con el catálogo restaurado");
        }
        try (var statement = db.prepareStatement("""
                update configuracion_backup set destino=jsonb_set(coalesce(destino,'{}'::jsonb),'{path}',to_jsonb(cast(? as text))),
                    backup_sequence=greatest(backup_sequence,?), version=version+1
                where id=(select configuracion_id from ejecucion_backup where id=?)
                """)) {
            statement.setString(1, destination.toString()); statement.setLong(2, sequence); statement.setObject(3, execution);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Falta la configuración de copias restaurada");
        }
        // Only local delivery bookkeeping changes. The old encrypted spool lives on the retired PC.
        try (var statement = db.prepareStatement("""
                update ejecucion_backup set metadata=coalesce(metadata,'{}'::jsonb) ||
                    '{"restoreSourceUnavailable":true}'::jsonb where id<>?
                """)) {
            statement.setObject(1, execution); statement.executeUpdate();
        }
        boolean hasCloudQueue;
        try (var statement = db.createStatement(); var rows = statement.executeQuery(
                "select to_regclass('cloud_backup_delivery') is not null")) {
            rows.next(); hasCloudQueue = rows.getBoolean(1);
        }
        if (hasCloudQueue) {
            try (var statement = db.createStatement()) {
                statement.executeUpdate("""
                        update cloud_backup_delivery set state='RETIRED',lease_token=null,lease_until=null,
                            error_reason='SOURCE_SPOOL_NOT_RECOVERED',updated_at=now()
                        where state<>'SENT'
                        """);
            }
        }
    }

    private static void requireSafePath(Path path) throws Exception {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("El destino no puede contener enlaces");
        }
    }

    private static String digest(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("La copia no es un archivo regular");
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

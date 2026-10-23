package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupManifestArchiveTest {
    @TempDir Path root;

    @Test void encryptedPackageAuthenticatesAndPreservesTheManifest() throws Exception {
        var archives = new BackupArchiveService();
        Path dump = Files.writeString(root.resolve("database.backup"), "database snapshot");
        Path archive = root.resolve("backup.zip");
        var manifest = Map.<String,Object>of("schemaVersion", 1, "installationId", UUID.randomUUID().toString(),
                "tableCounts", Map.of("public.producto", 42), "capturedAt", "2026-10-22T13:00:00Z");
        archives.create(dump, root.resolve("images"), root.resolve("templates"), archive, manifest);
        try (var zip = new ZipFile(archive.toFile())) {
            assertThat(zip.getEntry("manifest.json")).isNotNull();
            assertThat(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("42", "tableCounts");
        }
        var crypto = new BackupFileCrypto(1024);
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        Path encrypted = root.resolve("000001.tpvb");
        crypto.encrypt(archive, encrypted, key);
        assertThat(crypto.verify(encrypted, key).plaintextLength()).isEqualTo(Files.size(archive));
        crypto.decrypt(encrypted, root.resolve("verified.zip"), key);
        Path restored = root.resolve("restored.backup");
        archives.extractToStaging(root.resolve("verified.zip"), restored, root.resolve("images-stage"), root.resolve("templates-stage"));
        assertThat(Files.readString(restored)).isEqualTo("database snapshot");
        byte[] corrupt = Files.readAllBytes(encrypted); corrupt[corrupt.length - 1] ^= 1; Files.write(encrypted, corrupt);
        assertThatThrownBy(() -> crypto.verify(encrypted, key)).isInstanceOf(java.security.GeneralSecurityException.class);
        try (var files = Files.list(root)) { assertThat(files.noneMatch(p -> p.getFileName().toString().startsWith(".tpv-verify-"))).isTrue(); }
    }
}

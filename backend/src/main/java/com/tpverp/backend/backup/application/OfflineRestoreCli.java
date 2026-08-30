package com.tpverp.backend.backup.application;

import com.tpverp.backend.backup.BackupRestoreJournalReader;
import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Maintenance-only restore entry point. It deliberately has no Spring/web
 * dependency and therefore can be used while the backend service is stopped.
 * The caller must provide DB credentials through the normal PostgreSQL
 * environment (never as command-line arguments).
 */
public final class OfflineRestoreCli {
    private OfflineRestoreCli() { }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> args = parse(arguments);
        // Normalize every operator-supplied path before any filesystem operation.
        Path backup = required(args, "backup").toAbsolutePath().normalize();
        Path recovery = required(args, "recovery").toAbsolutePath().normalize();
        Path images = required(args, "images").toAbsolutePath().normalize();
        Path templates = required(args, "templates").toAbsolutePath().normalize();
        Path backupParent = backup.getParent();
        if (backupParent == null) throw new IllegalArgumentException("Backup sin directorio padre");
        Path staging = args.containsKey("staging") ? Path.of(args.get("staging")).toAbsolutePath().normalize()
                : backupParent.resolve(".tpv-restore-staging-" + UUID.randomUUID()).toAbsolutePath().normalize();
        String configuredJournal = args.get("journal");
        if (configuredJournal == null || configuredJournal.isBlank()) {
            configuredJournal = System.getenv("TPV_BACKUP_RESTORE_JOURNAL_PATH");
        }
        Path journal = configuredJournal == null || configuredJournal.isBlank()
                ? Path.of(BackupRestoreJournalReader.DEFAULT_PRODUCTION_PATH)
                : Path.of(configuredJournal);
        journal = journal.toAbsolutePath().normalize();
        String pgRestore = args.getOrDefault("pg-restore", "pg_restore");
        if (!safeRegular(backup) || !safeRegular(recovery)) {
            throw new IOException("Backup y recovery deben ser archivos regulares");
        }
        if (System.getenv("TPV_DB_URL") == null || System.getenv("TPV_DB_USERNAME") == null
                || System.getenv("PGPASSWORD") == null) {
            throw new IllegalStateException("TPV_DB_URL, TPV_DB_USERNAME y PGPASSWORD son obligatorios; no se inicia la restauración");
        }
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Se requiere consola segura para la clave de recuperación");
        char[] secret = console.readPassword("Clave de recuperación (no se mostrará): ");
        if (secret == null) throw new IllegalStateException("No se recibió clave de recuperación");
        Path archive = null;
        Path immutableBackup = null;
        byte[] recoveryPackage = null;
        byte[] brk = null;
        try {
            Path stagingParent = staging.getParent();
            if (stagingParent == null) throw new IllegalArgumentException("Staging sin directorio padre");
            Files.createDirectories(stagingParent);
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("No se sobrescribe un journal existente; use otra ruta y conserve la evidencia");
            }
            archive = Files.createTempFile(stagingParent, ".tpv-restore-", ".zip");
            immutableBackup = Files.createTempFile(stagingParent, ".tpv-source-", ".tpvb");
            Path dump = staging.resolve("database.backup");
            Path stagedImages = staging.resolve("images");
            Path stagedTemplates = staging.resolve("templates");
            String id = UUID.randomUUID().toString();
            String backupSha256 = sha256(backup);
            Files.createDirectories(staging);
            writeJournal(journal, id, "VALIDATING", backup, backupSha256, null, null);
            recoveryPackage = readBounded(recovery, 1024 * 1024);
            brk = new RecoveryKeyPackage().openAny(recoveryPackage, secret);
            copyNoFollow(backup, immutableBackup);
            if (!backupSha256.equalsIgnoreCase(sha256(immutableBackup))) throw new IllegalStateException("El backup cambió durante la copia segura");
            new BackupFileCrypto(1024 * 1024).decrypt(immutableBackup, archive, brk);
            if (!backupSha256.equalsIgnoreCase(sha256(immutableBackup))) throw new IllegalStateException("El backup cambió durante la validación");
            int maxEntries = envInt("TPV_BACKUP_ARCHIVE_MAX_ENTRIES", 100_000);
            long maxEntryBytes = envLong("TPV_BACKUP_ARCHIVE_MAX_ENTRY_BYTES", 256L * 1024 * 1024);
            long maxTotalBytes = envLong("TPV_BACKUP_ARCHIVE_MAX_TOTAL_BYTES", 2L * 1024 * 1024 * 1024);
            BackupArchiveService archives = new BackupArchiveService(maxEntries, maxEntryBytes, maxTotalBytes);
            archives.extractToStaging(archive, dump, stagedImages, stagedTemplates);
            writeJournal(journal, id, "STAGED", backup, backupSha256, staging, null);
            Path safetyRoot = createSafetyBackup(images, templates, journal.toAbsolutePath().normalize().getParent(), id);
            runDump(safetyRoot.resolve("database-current.backup"));
            writeJournal(journal, id, "SAFETY_BACKED_UP", backup, backupSha256, staging, safetyRoot);
            runRestore(pgRestore, dump);
            if (!backupSha256.equalsIgnoreCase(sha256(immutableBackup))) throw new IllegalStateException("El backup cambió durante la restauración");
            writeJournal(journal, id, "DATABASE_RESTORED", backup, backupSha256, staging, safetyRoot);
            archives.replaceTrees(stagedImages, images, stagedTemplates, templates);
            writeJournal(journal, id, "FILES_PROMOTED", backup, backupSha256, staging, safetyRoot);
            System.out.println("Restauración offline completada; ejecute el backend sin web con --spring.main.web-application-type=none --tpv.restore-finalize="
                    + journal.toAbsolutePath());
        } finally {
            Arrays.fill(secret, (char) 0);
            if (recoveryPackage != null) Arrays.fill(recoveryPackage, (byte) 0);
            if (brk != null) Arrays.fill(brk, (byte) 0);
            if (archive != null) deleteTree(archive);
            if (immutableBackup != null) deleteTree(immutableBackup);
            // Keep the journal and staged files on failure for forensic recovery.
            // Keep staging and safety evidence until the backend finalizer seals the journal.
        }
    }

    private static void runRestore(String command, Path dump) throws Exception {
        String url = System.getenv("TPV_DB_URL").replaceFirst("^jdbc:", "");
        ProcessBuilder process = new ProcessBuilder(command, "--clean", "--if-exists", "--single-transaction",
                "--no-password", "--username=" + System.getenv("TPV_DB_USERNAME"), "--dbname=" + url,
                dump.toAbsolutePath().toString());
        process.redirectErrorStream(true);
        Process child = process.start();
        readProcessOutput(child);
        if (child.waitFor() != 0) throw new IllegalStateException("pg_restore falló sin aplicar la promoción de ficheros");
    }

    private static void runDump(Path target) throws Exception {
        String command = System.getenv().getOrDefault("TPV_PG_DUMP_COMMAND", "pg_dump");
        String url = System.getenv("TPV_DB_URL").replaceFirst("^jdbc:", "");
        ProcessBuilder process = new ProcessBuilder(command, "--format=custom", "--no-password",
                "--username=" + System.getenv("TPV_DB_USERNAME"), "--file=" + target.toAbsolutePath(), "--dbname=" + url);
        process.redirectErrorStream(true);
        Process child = process.start();
        readProcessOutput(child);
        if (child.waitFor() != 0) throw new IllegalStateException("No se pudo crear el safety backup de PostgreSQL");
    }

    private static Path createSafetyBackup(Path images, Path templates, Path parent, String id) throws IOException {
        if (parent == null) throw new IOException("El journal no tiene directorio padre");
        Path root = parent.resolve(".tpv-safety-backup-" + id);
        Files.createDirectories(root);
        copyTreeIfExists(images, root.resolve("images"));
        copyTreeIfExists(templates, root.resolve("templates"));
        return root;
    }

    private static void copyTreeIfExists(Path source, Path target) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Árbol activo contiene un enlace o reparse point: " + path);
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (attributes.isDirectory()) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void writeJournal(Path path, String id, String phase, Path backup) throws IOException {
        writeJournal(path, id, phase, backup, sha256(backup), null, null);
    }
    private static void writeJournal(Path path, String id, String phase, Path backup, String backupSha256,
            Path staging, Path safetyRoot) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("Journal sin directorio padre");
        Files.createDirectories(parent);
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Properties previous = BackupRestoreJournalReader.read(normalized);
            if (!id.equals(BackupRestoreJournalReader.id(previous).toString())) {
                throw new IOException("No se puede sobrescribir un journal de otra restauración");
            }
        }
        Properties properties = new Properties();
        properties.setProperty("format", "TPV-RESTORE-JOURNAL-V2");
        properties.setProperty("id", id);
        properties.setProperty("phase", phase);
        properties.setProperty("backup", backup.toAbsolutePath().normalize().toString());
        properties.setProperty("backupSha256", backupSha256);
        properties.setProperty("updatedAt", Instant.now().toString());
        if (staging != null) properties.setProperty("staging", staging.toAbsolutePath().normalize().toString());
        if (safetyRoot != null) properties.setProperty("safetyRoot", safetyRoot.toAbsolutePath().normalize().toString());
        Path temporary = Files.createTempFile(parent, ".tpv-restore-journal-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                properties.store(output, "TPV ERP offline restore; no secrets");
            }
            try { Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String readProcessOutput(Process child) throws IOException {
        byte[] buffer = new byte[8192]; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int read; long total = 0;
        var input = child.getInputStream();
        while (true) {
            read = input.read(buffer);
            if (read < 0) break;
            if (read == 0) {
                int single = input.read();
                if (single < 0) break;
                if (total < 1_048_576) { output.write(single); total++; }
                continue;
            }
            if (total < 1_048_576) {
                int keep = (int) Math.min(read, 1_048_576 - total);
                output.write(buffer, 0, keep);
                total += keep;
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws IOException {
        try {
            if (!safeRegular(path)) throw new IOException("Backup no es un fichero regular seguro");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1024 * 1024]; int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) throw new IOException("Lectura sin progreso del backup");
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (Exception ex) { throw new IOException("No se pudo calcular la huella del backup", ex); }
    }

    private static boolean safeRegular(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther();
        } catch (IOException ex) { return false; }
    }

    private static byte[] readBounded(Path path, long maximum) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path.toAbsolutePath().normalize(),
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() > maximum) {
            throw new IOException("Archivo no regular seguro o demasiado grande");
        }
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 0 || size > maximum) throw new IOException("Archivo demasiado grande");
            byte[] result = new byte[(int) size];
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(result);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) throw new IOException("Archivo truncado");
                if (read == 0) throw new IOException("Lectura sin progreso del archivo");
            }
            return result;
        }
    }

    private static void copyNoFollow(Path source, Path target) throws IOException {
        try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var output = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long position = 0;
            long size = input.size();
            ByteBuffer fallback = ByteBuffer.allocate(1024 * 1024);
            while (position < size) {
                long moved = input.transferTo(position, size - position, output);
                if (moved <= 0) {
                    input.position(position);
                    fallback.clear();
                    int read = input.read(fallback);
                    if (read < 0) throw new IOException("Copia truncada del backup");
                    if (read == 0) throw new IOException("Copia sin progreso del backup");
                    fallback.flip();
                    while (fallback.hasRemaining()) output.write(fallback);
                    moved = read;
                }
                position += moved;
            }
        }
    }

    private static Map<String, String> parse(String[] arguments) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (int i = 0; i < arguments.length; i++) {
            if (!arguments[i].startsWith("--") || i + 1 >= arguments.length) throw new IllegalArgumentException("Argumento CLI inválido");
            parsed.put(arguments[i].substring(2), arguments[++i]);
        }
        return parsed;
    }
    private static Path required(Map<String, String> args, String name) {
        if (!args.containsKey(name)) throw new IllegalArgumentException("Falta --" + name);
        return Path.of(args.get(name));
    }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) { for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item); }
    }

    private static int envInt(String name, int fallback) {
        try { return Integer.parseInt(environmentValue(name, Integer.toString(fallback))); }
        catch (RuntimeException ex) { throw new IllegalStateException("Límite de backup inválido: " + name, ex); }
    }
    private static long envLong(String name, long fallback) {
        try { return Long.parseLong(environmentValue(name, Long.toString(fallback))); }
        catch (RuntimeException ex) { throw new IllegalStateException("Límite de backup inválido: " + name, ex); }
    }
    private static String environmentValue(String name, String fallback) {
        String productionName = switch (name) {
            case "TPV_BACKUP_ARCHIVE_MAX_ENTRIES" -> "TPV_BACKUP_PROD_MAX_ENTRIES";
            case "TPV_BACKUP_ARCHIVE_MAX_ENTRY_BYTES" -> "TPV_BACKUP_PROD_MAX_ENTRY_BYTES";
            case "TPV_BACKUP_ARCHIVE_MAX_TOTAL_BYTES" -> "TPV_BACKUP_PROD_MAX_TOTAL_BYTES";
            default -> name;
        };
        return System.getenv().getOrDefault(productionName,
                System.getenv().getOrDefault(name, fallback));
    }
}

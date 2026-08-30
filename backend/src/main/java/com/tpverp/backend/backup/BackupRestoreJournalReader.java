package com.tpverp.backend.backup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

/** Single bounded, no-follow reader for the offline restore journal. */
public final class BackupRestoreJournalReader {
    public static final String FORMAT = "TPV-RESTORE-JOURNAL-V2";
    public static final String DEFAULT_PRODUCTION_PATH =
            "C:\\ProgramData\\TPV ERP\\restore\\tpv-restore-journal.properties";
    public static final long MAX_BYTES = 1024L * 1024L;

    private BackupRestoreJournalReader() { }

    public static Path normalize(Path path) {
        if (path == null) throw new IllegalArgumentException("Falta la ruta del journal");
        return path.toAbsolutePath().normalize();
    }

    public static Properties read(Path path) {
        Path normalized = normalize(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(normalized,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || attributes.size() > MAX_BYTES) {
                throw new IOException("Journal no es un fichero regular seguro o excede 1 MiB");
            }
            Properties properties = new Properties();
            try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                long size = channel.size();
                if (size > MAX_BYTES) throw new IOException("Journal excede 1 MiB");
                byte[] content = new byte[(int) size];
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer);
                    if (read < 0) throw new IOException("Journal truncado");
                    if (read == 0) throw new IOException("Lectura vacía del journal");
                }
                try (var input = new java.io.ByteArrayInputStream(content)) {
                    properties.load(input);
                }
            }
            validate(properties);
            return properties;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("No se pudo leer el journal de restauración", ex);
        }
    }

    public static UUID id(Properties properties) {
        try { return UUID.fromString(required(properties, "id")); }
        catch (RuntimeException ex) { throw new IllegalStateException("Journal sin identificador UUID válido", ex); }
    }

    public static String hash(Properties properties) {
        String hash = required(properties, "backupSha256");
        if (!hash.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("Journal sin huella SHA-256 válida");
        }
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

    public static String phase(Properties properties) {
        String phase = required(properties, "phase");
        if (!phase.matches("VALIDATING|STAGED|SAFETY_BACKED_UP|DATABASE_RESTORED|FILES_PROMOTED|FINALIZED")) {
            throw new IllegalStateException("Fase de restauración no reconocida");
        }
        return phase;
    }

    public static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Journal sin " + key);
        return value.trim();
    }

    public static String sha256(Path path) {
        Path normalized = normalize(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(normalized,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Backup no es un fichero regular seguro");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                while (true) {
                    buffer.clear();
                    int read = channel.read(buffer);
                    if (read < 0) break;
                    if (read == 0) throw new IOException("Lectura vacía del backup");
                    digest.update(buffer.array(), 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo validar la huella del backup", ex);
        }
    }

    private static void validate(Properties properties) {
        if (!FORMAT.equals(required(properties, "format"))) {
            throw new IllegalStateException("Journal de restauración no reconocido");
        }
        id(properties);
        hash(properties);
        phase(properties);
        Path backup = Path.of(required(properties, "backup"));
        if (!backup.isAbsolute()) throw new IllegalStateException("Ruta de backup no absoluta");
        for (String key : new String[] {"staging", "safetyRoot"}) {
            String optional = properties.getProperty(key);
            if (optional != null && !optional.isBlank() && !Path.of(optional.trim()).isAbsolute()) {
                throw new IllegalStateException("Ruta " + key + " no absoluta");
            }
        }
    }
}

package com.tpverp.backend.backup.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Creates and validates the non-fiscal file part of a backup. */
public class BackupArchiveService {
    private static final String DATABASE_ENTRY = "database.backup";
    private static final String IMAGES_PREFIX = "images/";
    private static final String TEMPLATES_PREFIX = "templates/";
    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxTotalBytes;
    private final TreeMoveOperation treeMover;

    public BackupArchiveService() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public BackupArchiveService(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        this(maxEntries, maxEntryBytes, maxTotalBytes, BackupArchiveService::moveReplacing);
    }

    BackupArchiveService(TreeMoveOperation treeMover) {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES, treeMover);
    }

    private BackupArchiveService(
            int maxEntries,
            long maxEntryBytes,
            long maxTotalBytes,
            TreeMoveOperation treeMover) {
        if (maxEntries < 1 || maxEntryBytes < 1 || maxTotalBytes < maxEntryBytes) {
            throw new IllegalArgumentException("Los limites del archivo de backup no son validos");
        }
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.treeMover = Objects.requireNonNull(treeMover, "treeMover");
    }

    public BackupArchiveInfo create(Path databaseDump, Path imagesRoot, Path archive) throws IOException {
        return create(databaseDump, imagesRoot, null, archive);
    }

    public BackupArchiveInfo create(Path databaseDump, Path imagesRoot, Path templatesRoot, Path archive)
            throws IOException {
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        Path normalizedDump = databaseDump.toAbsolutePath().normalize();
        requireRegularFile(normalizedDump, "El volcado de base de datos no es un archivo regular");
        rejectArchiveInsideTree(normalizedArchive, imagesRoot);
        rejectArchiveInsideTree(normalizedArchive, templatesRoot);
        Files.createDirectories(requireParent(normalizedArchive));
        long imageFiles = 0;
        long templateFiles = 0;
        long databaseBytes;
        long totalBytes = 0;
        int entries = 0;
        try (var output = new ZipOutputStream(Files.newOutputStream(
                normalizedArchive, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            entries = requireEntryCapacity(entries);
            output.putNextEntry(new ZipEntry(DATABASE_ENTRY));
            try (var input = Files.newInputStream(
                    normalizedDump, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                databaseBytes = copyLimited(input, output, maxEntryBytes, maxTotalBytes);
                totalBytes += databaseBytes;
            }
            output.closeEntry();
            var imageResult = writeOptionalTree(imagesRoot, IMAGES_PREFIX, output, entries, totalBytes);
            imageFiles = imageResult.fileCount();
            entries = imageResult.entries();
            totalBytes = imageResult.totalBytes();
            var templateResult = writeOptionalTree(templatesRoot, TEMPLATES_PREFIX, output, entries, totalBytes);
            templateFiles = templateResult.fileCount();
        } catch (Exception exception) {
            Files.deleteIfExists(normalizedArchive);
            if (exception instanceof IOException io) {
                throw io;
            }
            throw new IOException("No se pudo crear el archivo de backup", exception);
        }
        return new BackupArchiveInfo(databaseBytes, imageFiles, templateFiles);
    }

    /** Extracts and validates an archive without deleting any active tree. */
    public BackupArchiveInfo extractToStaging(
            Path archive, Path databaseDump, Path imagesStaging, Path templatesStaging) throws IOException {
        Path normalizedDump = databaseDump.toAbsolutePath().normalize();
        Path normalizedImages = prepareEmptyStaging(imagesStaging);
        Path normalizedTemplates = templatesStaging == null ? null : prepareEmptyStaging(templatesStaging);
        Files.deleteIfExists(normalizedDump);
        Files.createDirectories(requireParent(normalizedDump));
        long databaseBytes = 0;
        long imageFiles = 0;
        long templateFiles = 0;
        long totalBytes = 0;
        int entries = 0;
        boolean databaseSeen = false;
        Set<String> names = new HashSet<>();
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IOException("El archivo de backup contiene demasiadas entradas");
                String name = safeEntryName(entry.getName());
                String canonicalName = canonicalEntryName(name);
                if (!names.add(canonicalName)) throw new IOException("El archivo de backup contiene entradas duplicadas");
                if (entry.isDirectory()) {
                    if (DATABASE_ENTRY.equals(name)) throw new IOException("database.backup no puede ser un directorio");
                    continue;
                }
                Path target;
                if (DATABASE_ENTRY.equals(name)) {
                    databaseSeen = true;
                    target = normalizedDump;
                } else if (name.startsWith(IMAGES_PREFIX)) {
                    target = safeTarget(normalizedImages, name.substring(IMAGES_PREFIX.length()));
                    imageFiles++;
                } else if (name.startsWith(TEMPLATES_PREFIX) && normalizedTemplates != null) {
                    target = safeTarget(normalizedTemplates, name.substring(TEMPLATES_PREFIX.length()));
                    templateFiles++;
                } else if (name.startsWith(TEMPLATES_PREFIX)) {
                    // The legacy overload deliberately ignores templates, but still
                    // consumes and limits their bytes so they cannot bypass validation.
                    totalBytes += copyLimited(input, (Path) null, maxEntryBytes, maxTotalBytes - totalBytes);
                    continue;
                } else {
                    throw new IOException("Entrada no permitida en el archivo de backup: " + name);
                }
                long written = copyLimited(input, target, maxEntryBytes, maxTotalBytes - totalBytes);
                totalBytes += written;
                if (DATABASE_ENTRY.equals(name)) databaseBytes = written;
            }
        } catch (Exception exception) {
            cleanup(normalizedDump, normalizedImages, normalizedTemplates);
            if (exception instanceof IOException io) throw io;
            throw new IOException("No se pudo validar el archivo de backup", exception);
        }
        if (!databaseSeen) {
            cleanup(normalizedDump, normalizedImages, normalizedTemplates);
            throw new IOException("El archivo de backup no contiene database.backup");
        }
        return new BackupArchiveInfo(databaseBytes, imageFiles, templateFiles);
    }

    /** Compatibility wrapper: stages and promotes only after full validation. */
    public void restore(Path archive, Path databaseDump, Path imagesRoot) throws IOException {
        restore(archive, databaseDump, imagesRoot, null);
    }

    public void restore(Path archive, Path databaseDump, Path imagesRoot, Path templatesRoot) throws IOException {
        Path imageStage = Files.createTempDirectory(requireParent(imagesRoot.toAbsolutePath().normalize()), ".tpv-images-restore-");
        Path templateStage = templatesRoot == null ? null
                : Files.createTempDirectory(requireParent(templatesRoot.toAbsolutePath().normalize()), ".tpv-templates-restore-");
        Path dumpStage = Files.createTempFile(requireParent(databaseDump.toAbsolutePath().normalize()), ".tpv-dump-restore-", ".backup");
        try {
            extractToStaging(archive, dumpStage, imageStage, templateStage);
            Files.move(dumpStage, databaseDump.toAbsolutePath().normalize(), StandardCopyOption.REPLACE_EXISTING);
            replaceTrees(imageStage, imagesRoot, templateStage, templatesRoot);
            imageStage = null;
            templateStage = null;
        } finally {
            Files.deleteIfExists(dumpStage);
            if (imageStage != null) deleteDirectory(imageStage);
            if (templateStage != null) deleteDirectory(templateStage);
        }
    }

    /** Promotes already validated trees, retaining backups until both swaps succeed. */
    public void replaceTrees(Path imagesStaging, Path imagesRoot, Path templatesStaging, Path templatesRoot)
            throws IOException {
        requirePromotionTree(imagesStaging, "imagenes");
        if (templatesRoot != null) requirePromotionTree(templatesStaging, "plantillas");
        requireDistinctTrees(imagesRoot, templatesRoot);
        Path imageBackup = siblingBackup(imagesRoot, "images");
        Path templateBackup = templatesRoot == null ? null : siblingBackup(templatesRoot, "templates");
        boolean imageOriginalMoved = false;
        boolean imageStagingPromoted = false;
        boolean templateOriginalMoved = false;
        boolean templateStagingPromoted = false;
        boolean safeToDeleteBackups = false;
        try {
            imageOriginalMoved = moveExisting(imagesRoot, imageBackup);
            imageStagingPromoted = moveExisting(imagesStaging, imagesRoot);
            if (!imageStagingPromoted) {
                throw new IOException("El staging de imagenes desaparecio antes de su promocion");
            }
            if (templatesRoot != null) {
                templateOriginalMoved = moveExisting(templatesRoot, templateBackup);
                templateStagingPromoted = moveExisting(templatesStaging, templatesRoot);
                if (!templateStagingPromoted) {
                    throw new IOException("El staging de plantillas desaparecio antes de su promocion");
                }
            }
            safeToDeleteBackups = true;
        } catch (Exception exception) {
            IOException rollbackFailure = null;
            try {
                if (templatesRoot != null) {
                    rollbackTree(
                            templatesRoot,
                            templateBackup,
                            templateOriginalMoved,
                            templateStagingPromoted,
                            "plantillas");
                }
            } catch (Exception rollback) {
                rollbackFailure = new IOException("No se pudo revertir el arbol de plantillas", rollback);
            }
            try {
                rollbackTree(
                        imagesRoot,
                        imageBackup,
                        imageOriginalMoved,
                        imageStagingPromoted,
                        "imagenes");
            } catch (Exception rollback) {
                if (rollbackFailure == null) {
                    rollbackFailure = new IOException("No se pudo revertir el arbol de imagenes", rollback);
                } else {
                    rollbackFailure.addSuppressed(rollback);
                }
            }
            if (rollbackFailure != null) {
                rollbackFailure.addSuppressed(exception);
                throw new IOException(
                        "No se pudo restaurar los arboles ni completar su rollback; se conservan las copias temporales",
                        rollbackFailure);
            }
            safeToDeleteBackups = true;
            throw exception instanceof IOException io ? io : new IOException("No se pudo promover el arbol restaurado", exception);
        } finally {
            if (safeToDeleteBackups) {
                deleteDirectory(imageBackup);
                if (templateBackup != null) deleteDirectory(templateBackup);
            }
        }
    }

    private void rollbackTree(
            Path activeRoot,
            Path backupRoot,
            boolean originalMoved,
            boolean stagingPromoted,
            String label) throws IOException {
        if (!originalMoved && !stagingPromoted) {
            return;
        }
        if (originalMoved) {
            // The original is known to be in backupRoot. Only in this state may
            // rollback remove whatever a failed promotion left at activeRoot.
            if (!Files.exists(backupRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Falta la copia rollback del arbol de " + label);
            }
            requireDirectoryWithoutLinks(
                    backupRoot.toAbsolutePath().normalize(),
                    "La copia rollback del arbol de " + label + " no es segura");
            deleteDirectory(activeRoot);
            treeMover.move(
                    backupRoot.toAbsolutePath().normalize(),
                    activeRoot.toAbsolutePath().normalize());
            return;
        }
        // No original existed. A promoted staging tree must be removed to
        // restore that absence; if promotion never completed, activeRoot is
        // deliberately left untouched.
        deleteDirectory(activeRoot);
    }

    private TreeWriteResult writeOptionalTree(
            Path configuredRoot,
            String prefix,
            ZipOutputStream output,
            int entries,
            long totalBytes) throws IOException {
        if (configuredRoot == null) return new TreeWriteResult(0, entries, totalBytes);
        Path root = configuredRoot.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return new TreeWriteResult(0, entries, totalBytes);
        requireDirectoryWithoutLinks(root, "El arbol de backup no es un directorio seguro");
        long count = 0;
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                BasicFileAttributes attributes = readNoFollowAttributes(path);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException("El arbol de backup contiene un enlace simbolico o punto de reanalisis");
                }
                if (!attributes.isRegularFile()) continue;
                entries = requireEntryCapacity(entries);
                String relative = root.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new ZipEntry(prefix + relative));
                try (var input = Files.newInputStream(
                        path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    totalBytes += copyLimited(input, output, maxEntryBytes, maxTotalBytes - totalBytes);
                }
                output.closeEntry();
                count++;
            }
        }
        return new TreeWriteResult(count, entries, totalBytes);
    }

    private static String safeEntryName(String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.startsWith("/")
                || name.startsWith("\\") || name.contains(":") || name.contains("\\")) {
            throw new IOException("Ruta insegura en el archivo de backup");
        }
        Path path = Path.of(name).normalize();
        if (path.isAbsolute() || path.startsWith("..")) throw new IOException("Entrada fuera del destino de restauracion");
        for (Path part : path) {
            String segment = part.toString();
            if (segment.endsWith(".") || segment.endsWith(" ") || isWindowsReservedName(segment)) {
                throw new IOException("Nombre incompatible con una restauracion segura en Windows");
            }
        }
        return name;
    }

    private static Path safeTarget(Path root, String relative) throws IOException {
        Path target = root.resolve(safeEntryName(relative)).normalize();
        if (!target.startsWith(root) || isLinkOrReparsePoint(root)) throw new IOException("Entrada fuera del destino de restauracion");
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (isLinkOrReparsePoint(current)) throw new IOException("El destino contiene un enlace o punto de reanalisis");
        }
        Files.createDirectories(target.getParent());
        return target;
    }

    private static long copyLimited(ZipInputStream input, Path target, long entryLimit, long totalRemaining) throws IOException {
        try (var output = target == null ? null : Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            return copyLimited(input, output, entryLimit, totalRemaining);
        }
    }

    private static long copyLimited(InputStream input, OutputStream output, long entryLimit, long totalRemaining)
            throws IOException {
        long written = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            written += read;
            if (written > entryLimit || written > totalRemaining) {
                throw new IOException("El archivo de backup excede sus limites de tamano");
            }
            if (output != null) output.write(buffer, 0, read);
        }
        return written;
    }

    private static Path prepareEmptyStaging(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (isLinkOrReparsePoint(normalized)) throw new IOException("El staging no puede ser un enlace o punto de reanalisis");
            deleteDirectory(normalized);
        }
        Files.createDirectories(normalized);
        return normalized;
    }

    private static Path requireParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("La ruta de backup no tiene directorio padre");
        Files.createDirectories(parent);
        return parent;
    }

    private static Path siblingBackup(Path target, String prefix) throws IOException {
        Path placeholder = Files.createTempDirectory(
                requireParent(target.toAbsolutePath().normalize()), ".tpv-" + prefix + "-rollback-");
        Files.deleteIfExists(placeholder);
        return placeholder;
    }

    private boolean moveExisting(Path source, Path destination) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (!Files.exists(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        if (isLinkOrReparsePoint(normalized)) throw new IOException("El arbol activo no puede ser un enlace o punto de reanalisis");
        treeMover.move(normalized, destination.toAbsolutePath().normalize());
        return true;
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        // Replacing a live tree is safe only as a filesystem rename. ATOMIC_MOVE
        // makes a cross-volume promotion fail explicitly instead of silently
        // degrading to a non-atomic copy/delete sequence; replaceTrees then
        // restores any original tree already moved to its sibling backup.
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void cleanup(Path dump, Path images, Path templates) throws IOException {
        Files.deleteIfExists(dump);
        deleteDirectory(images);
        if (templates != null) deleteDirectory(templates);
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (isLinkOrReparsePoint(directory)) { Files.deleteIfExists(directory); return; }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private int requireEntryCapacity(int currentEntries) throws IOException {
        if (currentEntries >= maxEntries) throw new IOException("El archivo de backup contiene demasiadas entradas");
        return currentEntries + 1;
    }

    private static String canonicalEntryName(String name) throws IOException {
        String normalized = Path.of(safeEntryName(name)).normalize().toString().replace('\\', '/');
        return Normalizer.normalize(normalized, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean isWindowsReservedName(String segment) {
        String base = segment.strip().split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return base.equals("CON") || base.equals("PRN") || base.equals("AUX") || base.equals("NUL")
                || base.matches("COM[1-9]") || base.matches("LPT[1-9]");
    }

    private static BasicFileAttributes readNoFollowAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isLinkOrReparsePoint(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        BasicFileAttributes attributes = readNoFollowAttributes(path);
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static void requireRegularFile(Path path, String message) throws IOException {
        BasicFileAttributes attributes = readNoFollowAttributes(path);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException(message);
        }
    }

    private static void requireDirectoryWithoutLinks(Path path, String message) throws IOException {
        BasicFileAttributes attributes = readNoFollowAttributes(path);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException(message);
        }
    }

    private static void rejectArchiveInsideTree(Path archive, Path configuredRoot) throws IOException {
        if (configuredRoot == null) return;
        Path root = configuredRoot.toAbsolutePath().normalize();
        if (archive.startsWith(root)) {
            throw new IOException("El archivo de backup no puede crearse dentro de un arbol incluido en el propio backup");
        }
    }

    private static void requirePromotionTree(Path staging, String label) throws IOException {
        if (staging == null) throw new IOException("Falta el staging de " + label);
        Path normalized = staging.toAbsolutePath().normalize();
        try {
            requireDirectoryWithoutLinks(normalized, "El staging de " + label + " no es seguro");
        } catch (IOException exception) {
            throw new IOException("El staging de " + label + " no existe o no es seguro: " + normalized, exception);
        }
    }

    private static void requireDistinctTrees(Path imagesRoot, Path templatesRoot) throws IOException {
        if (templatesRoot == null) return;
        Path images = imagesRoot.toAbsolutePath().normalize();
        Path templates = templatesRoot.toAbsolutePath().normalize();
        if (images.equals(templates) || images.startsWith(templates) || templates.startsWith(images)) {
            throw new IOException("Los arboles activos de imagenes y plantillas deben ser independientes");
        }
    }

    public record BackupArchiveInfo(long databaseBytes, long imageFiles, long templateFiles) { }

    private record TreeWriteResult(long fileCount, int entries, long totalBytes) { }

    @FunctionalInterface
    interface TreeMoveOperation {
        void move(Path source, Path destination) throws IOException;
    }
}

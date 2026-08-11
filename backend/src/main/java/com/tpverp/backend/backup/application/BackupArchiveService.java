package com.tpverp.backend.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class BackupArchiveService {

    private static final String DATABASE_ENTRY = "database.backup";
    private static final String IMAGES_PREFIX = "images/";
    private static final String TEMPLATES_PREFIX = "templates/";

    public BackupArchiveInfo create(Path databaseDump, Path imagesRoot, Path archive) throws IOException {
        return create(databaseDump, imagesRoot, null, archive);
    }

    public BackupArchiveInfo create(
            Path databaseDump,
            Path imagesRoot,
            Path templatesRoot,
            Path archive) throws IOException {
        Files.createDirectories(archive.toAbsolutePath().getParent());
        long imageFiles = 0;
        long templateFiles = 0;
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(DATABASE_ENTRY));
            Files.copy(databaseDump, output);
            output.closeEntry();
            if (Files.isDirectory(imagesRoot)) {
                imageFiles = writeTree(
                        imagesRoot.toAbsolutePath().normalize(), IMAGES_PREFIX, output);
            }
            if (templatesRoot != null && Files.isDirectory(templatesRoot)) {
                templateFiles = writeTree(
                        templatesRoot.toAbsolutePath().normalize(), TEMPLATES_PREFIX, output);
            }
        }
        return new BackupArchiveInfo(Files.size(databaseDump), imageFiles, templateFiles);
    }
    // Empaqueta dump e imagenes antes del cifrado del backup.

    public void restore(Path archive, Path databaseDump, Path imagesRoot) throws IOException {
        restore(archive, databaseDump, imagesRoot, null);
    }

    public void restore(
            Path archive,
            Path databaseDump,
            Path imagesRoot,
            Path templatesRoot) throws IOException {
        Path normalizedImagesRoot = imagesRoot.toAbsolutePath().normalize();
        deleteDirectory(normalizedImagesRoot);
        Files.createDirectories(normalizedImagesRoot);
        Path normalizedTemplatesRoot = templatesRoot == null
                ? null : templatesRoot.toAbsolutePath().normalize();
        if (normalizedTemplatesRoot != null) {
            deleteDirectory(normalizedTemplatesRoot);
            Files.createDirectories(normalizedTemplatesRoot);
        }
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (DATABASE_ENTRY.equals(entry.getName())) {
                    Files.copy(input, databaseDump, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if (entry.getName().startsWith(IMAGES_PREFIX)) {
                    restoreEntry(input, entry, IMAGES_PREFIX, normalizedImagesRoot);
                } else if (normalizedTemplatesRoot != null
                        && entry.getName().startsWith(TEMPLATES_PREFIX)) {
                    restoreEntry(input, entry, TEMPLATES_PREFIX, normalizedTemplatesRoot);
                }
            }
        }
    }
    // Restaura primero el dump y reemplaza el arbol de imagenes del backup.

    private static long writeTree(
            Path root, String prefix, ZipOutputStream output) throws IOException {
        long count = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                var relative = root.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new ZipEntry(prefix + relative));
                Files.copy(path, output);
                output.closeEntry();
                count++;
            }
        }
        return count;
    }

    private static void restoreEntry(
            ZipInputStream input,
            ZipEntry entry,
            String prefix,
            Path root) throws IOException {
        Path target = root.resolve(entry.getName().substring(prefix.length())).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Entrada fuera del destino de restauracion");
        }
        Files.createDirectories(target.getParent());
        Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record BackupArchiveInfo(
            long databaseBytes, long imageFiles, long templateFiles) {
    }
}

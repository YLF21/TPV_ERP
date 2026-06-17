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

    public BackupArchiveInfo create(Path databaseDump, Path imagesRoot, Path archive) throws IOException {
        Files.createDirectories(archive.toAbsolutePath().getParent());
        long imageFiles = 0;
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(DATABASE_ENTRY));
            Files.copy(databaseDump, output);
            output.closeEntry();
            if (Files.isDirectory(imagesRoot)) {
                imageFiles = writeImages(imagesRoot.toAbsolutePath().normalize(), output);
            }
        }
        return new BackupArchiveInfo(Files.size(databaseDump), imageFiles);
    }
    // Empaqueta dump e imagenes antes del cifrado del backup.

    public void restore(Path archive, Path databaseDump, Path imagesRoot) throws IOException {
        Path normalizedImagesRoot = imagesRoot.toAbsolutePath().normalize();
        deleteDirectory(normalizedImagesRoot);
        Files.createDirectories(normalizedImagesRoot);
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (DATABASE_ENTRY.equals(entry.getName())) {
                    Files.copy(input, databaseDump, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else if (entry.getName().startsWith(IMAGES_PREFIX)) {
                    Path target = normalizedImagesRoot.resolve(
                            entry.getName().substring(IMAGES_PREFIX.length())).normalize();
                    if (!target.startsWith(normalizedImagesRoot)) {
                        throw new IOException("Entrada de imagen fuera del destino");
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
    // Restaura primero el dump y reemplaza el arbol de imagenes del backup.

    private static long writeImages(Path imagesRoot, ZipOutputStream output) throws IOException {
        long count = 0;
        try (var paths = Files.walk(imagesRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                var relative = imagesRoot.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new ZipEntry(IMAGES_PREFIX + relative));
                Files.copy(path, output);
                output.closeEntry();
                count++;
            }
        }
        return count;
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

    public record BackupArchiveInfo(long databaseBytes, long imageFiles) {
    }
}

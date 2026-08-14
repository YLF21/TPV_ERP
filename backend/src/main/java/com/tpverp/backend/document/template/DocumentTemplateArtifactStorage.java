package com.tpverp.backend.document.template;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class DocumentTemplateArtifactStorage {

    private static final String SOURCE_FILE = "source.jrxml";
    private static final String COMPILED_FILE = "compiled.jasper";
    private static final String SOURCES_DIRECTORY = "sources";
    private static final String COMPILED_DIRECTORY = "compiled";

    private final Path root;

    public DocumentTemplateArtifactStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public StoredArtifact write(UUID templateId, byte[] source, byte[] compiled) throws IOException {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(compiled, "compiled");
        Path directory = directory(templateId.toString());
        Files.createDirectories(directory);
        Path sourceTemporary = Files.createTempFile(directory, ".source-", ".tmp");
        Path compiledTemporary = Files.createTempFile(directory, ".compiled-", ".tmp");
        Path sourceTarget = directory.resolve(SOURCE_FILE);
        Path compiledTarget = directory.resolve(COMPILED_FILE);
        try {
            Files.write(sourceTemporary, source);
            Files.write(compiledTemporary, compiled);
            move(sourceTemporary, sourceTarget);
            move(compiledTemporary, compiledTarget);
            return new StoredArtifact(templateId.toString());
        } catch (IOException exception) {
            Files.deleteIfExists(sourceTarget);
            Files.deleteIfExists(compiledTarget);
            throw exception;
        } finally {
            Files.deleteIfExists(sourceTemporary);
            Files.deleteIfExists(compiledTemporary);
        }
    }

    public StoredArtifact writeBundle(
            UUID templateId,
            String masterFilename,
            Map<String, TicketJrxmlBundleCompiler.CompiledReport> reports) throws IOException {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(masterFilename, "masterFilename");
        Objects.requireNonNull(reports, "reports");
        var master = Objects.requireNonNull(reports.get(masterFilename), "masterReport");
        Path directory = directory(templateId.toString());
        Files.createDirectories(root);
        Path temporary = Files.createTempDirectory(root, ".bundle-");
        try {
            Path sources = temporary.resolve(SOURCES_DIRECTORY);
            Path compiled = temporary.resolve(COMPILED_DIRECTORY);
            Files.createDirectories(sources);
            Files.createDirectories(compiled);
            for (var entry : reports.entrySet()) {
                String filename = safeFilename(entry.getKey(), ".jrxml");
                Files.write(sources.resolve(filename), entry.getValue().source());
                Files.write(compiled.resolve(replaceExtension(filename, ".jasper")),
                        entry.getValue().compiled());
            }
            Files.write(temporary.resolve(SOURCE_FILE), master.source());
            Files.write(temporary.resolve(COMPILED_FILE), master.compiled());
            if (Files.exists(directory)) {
                delete(templateId.toString());
            }
            move(temporary, directory);
            return new StoredArtifact(templateId.toString());
        } finally {
            if (Files.exists(temporary)) {
                deleteDirectory(temporary);
            }
        }
    }

    public byte[] readSource(String reference) throws IOException {
        return Files.readAllBytes(directory(reference).resolve(SOURCE_FILE));
    }

    public byte[] readCompiled(String reference) throws IOException {
        return Files.readAllBytes(directory(reference).resolve(COMPILED_FILE));
    }

    public boolean isBundle(String reference) {
        return Files.isDirectory(directory(reference).resolve(SOURCES_DIRECTORY));
    }

    public Map<String, byte[]> readBundleSources(String reference) throws IOException {
        Path sources = directory(reference).resolve(SOURCES_DIRECTORY);
        if (!Files.isDirectory(sources)) {
            throw new IOException("document_template_bundle_not_available");
        }
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.list(sources)) {
            for (Path source : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(source.getFileName().toString(), Files.readAllBytes(source));
            }
        }
        return Map.copyOf(result);
    }

    public Path compiledBundleMaster(String reference, String masterFilename) throws IOException {
        String filename = replaceExtension(safeFilename(masterFilename, ".jrxml"), ".jasper");
        Path target = directory(reference).resolve(COMPILED_DIRECTORY).resolve(filename).normalize();
        if (!Files.isRegularFile(target)) {
            throw new IOException("document_template_compiled_bundle_not_available");
        }
        return target;
    }

    public void delete(String reference) throws IOException {
        Path directory = directory(reference);
        if (!Files.isDirectory(directory)) {
            return;
        }
        deleteDirectory(directory);
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public Path root() {
        return root;
    }

    private Path directory(String reference) {
        UUID templateId;
        try {
            templateId = UUID.fromString(Objects.requireNonNull(reference, "reference"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("document_template_artifact_reference_invalid", exception);
        }
        Path resolved = root.resolve(templateId.toString()).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("document_template_artifact_reference_invalid");
        }
        return resolved;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeFilename(String value, String extension) {
        String filename = Objects.requireNonNull(value, "filename");
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9_-]*\\Q" + extension + "\\E")) {
            throw new IllegalArgumentException("document_template_bundle_filename_invalid");
        }
        return filename;
    }

    private static String replaceExtension(String filename, String extension) {
        return filename.substring(0, filename.lastIndexOf('.')) + extension;
    }

    public record StoredArtifact(String reference) {
    }
}

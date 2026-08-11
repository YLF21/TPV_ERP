package com.tpverp.backend.document.template;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

public class DocumentTemplateArtifactStorage {

    private static final String SOURCE_FILE = "source.jrxml";
    private static final String COMPILED_FILE = "compiled.jasper";

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

    public byte[] readSource(String reference) throws IOException {
        return Files.readAllBytes(directory(reference).resolve(SOURCE_FILE));
    }

    public byte[] readCompiled(String reference) throws IOException {
        return Files.readAllBytes(directory(reference).resolve(COMPILED_FILE));
    }

    public void delete(String reference) throws IOException {
        Path directory = directory(reference);
        if (!Files.isDirectory(directory)) {
            return;
        }
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

    public record StoredArtifact(String reference) {
    }
}

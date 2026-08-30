package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupArchiveServiceTest {

    @TempDir
    private Path tempDir;

    private final BackupArchiveService service = new BackupArchiveService();

    @Test
    void packsDatabaseDumpImagesAndTemplatesAndRestoresAll() throws Exception {
        Path dump = tempDir.resolve("database.backup");
        Path images = tempDir.resolve("images");
        Path image = images.resolve("store/product/image.webp");
        Path templates = tempDir.resolve("templates");
        Path template = templates.resolve("template-id/source.jrxml");
        Path archive = tempDir.resolve("backup.zip");
        Path restoredDump = tempDir.resolve("restored.backup");
        Path restoredImages = tempDir.resolve("restored-images");
        Path restoredTemplates = tempDir.resolve("restored-templates");
        Files.createDirectories(image.getParent());
        Files.createDirectories(template.getParent());
        Files.writeString(dump, "pg dump");
        Files.write(image, new byte[] {1, 2, 3});
        Files.writeString(template, "<jasperReport/>");

        var info = service.create(dump, images, templates, archive);
        service.restore(archive, restoredDump, restoredImages, restoredTemplates);

        assertThat(info.databaseBytes()).isEqualTo(7);
        assertThat(info.imageFiles()).isEqualTo(1);
        assertThat(info.templateFiles()).isEqualTo(1);
        assertThat(Files.readString(restoredDump)).isEqualTo("pg dump");
        assertThat(Files.readAllBytes(restoredImages.resolve("store/product/image.webp")))
                .containsExactly(1, 2, 3);
        assertThat(Files.readString(restoredTemplates.resolve("template-id/source.jrxml")))
                .isEqualTo("<jasperReport/>");
    }

    @Test
    void excludesVerifactuSecretsFromArchive() throws Exception {
        var dump = tempDir.resolve("database.backup");
        var images = tempDir.resolve("images");
        var secrets = tempDir.resolve("verifactu-secrets");
        var archive = tempDir.resolve("backup.zip");
        Files.createDirectories(images);
        Files.createDirectories(secrets);
        Files.writeString(dump, "pg dump");
        Files.writeString(secrets.resolve("private-key.dpapi"), "secret");

        service.create(dump, images, archive);

        assertThat(entries(archive)).containsExactly("database.backup");
    }

    @Test
    void rejectsMissingAndDuplicateDatabaseEntriesWithoutTouchingActiveTree() throws Exception {
        var images = tempDir.resolve("active-images");
        Files.createDirectories(images);
        Files.writeString(images.resolve("keep.txt"), "keep");
        var missing = tempDir.resolve("missing.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(missing))) {
            output.putNextEntry(new java.util.zip.ZipEntry("images/new.txt"));
            output.write(1);
            output.closeEntry();
        }
        assertThatThrownBy(() -> service.restore(
                missing, tempDir.resolve("dump"), images, null))
                .hasMessageContaining("database.backup");
        assertThat(Files.readString(images.resolve("keep.txt"))).isEqualTo("keep");

    }

    @Test
    void rejectsZipSlipEntry() throws Exception {
        var archive = tempDir.resolve("zip-slip.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new java.util.zip.ZipEntry("database.backup"));
            output.write(1);
            output.closeEntry();
            output.putNextEntry(new java.util.zip.ZipEntry("images/../../outside.txt"));
            output.write(1);
            output.closeEntry();
        }
        assertThatThrownBy(() -> service.extractToStaging(
                archive, tempDir.resolve("dump"), tempDir.resolve("stage-images"), null))
                .hasMessageContaining("Entrada fuera");
    }

    @Test
    void enforcesEntryAndTotalArchiveLimits() throws Exception {
        var limited = new BackupArchiveService(4, 4, 8);
        var archive = tempDir.resolve("oversized.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new java.util.zip.ZipEntry("database.backup"));
            output.write(new byte[] {1, 2, 3, 4, 5});
            output.closeEntry();
        }
        assertThatThrownBy(() -> limited.extractToStaging(
                archive, tempDir.resolve("dump"), tempDir.resolve("stage-images"), null))
                .hasMessageContaining("limites");
    }

    @Test
    void enforcesLimitsWhileCreatingSoItNeverProducesAnArchiveItCannotRestore() throws Exception {
        var limited = new BackupArchiveService(4, 4, 8);
        var dump = tempDir.resolve("large-database.backup");
        var archive = tempDir.resolve("large-backup.zip");
        Files.write(dump, new byte[] {1, 2, 3, 4, 5});

        assertThatThrownBy(() -> limited.create(dump, tempDir.resolve("missing-images"), archive))
                .hasMessageContaining("limites");
        assertThat(archive).doesNotExist();
    }

    @Test
    void rejectsCaseInsensitiveDuplicateTargetsBeforeWritingOnWindows() throws Exception {
        var archive = tempDir.resolve("case-duplicates.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new java.util.zip.ZipEntry("database.backup"));
            output.write(1);
            output.closeEntry();
            output.putNextEntry(new java.util.zip.ZipEntry("images/Product.JPG"));
            output.write(2);
            output.closeEntry();
            output.putNextEntry(new java.util.zip.ZipEntry("images/product.jpg"));
            output.write(3);
            output.closeEntry();
        }

        assertThatThrownBy(() -> service.extractToStaging(
                archive, tempDir.resolve("case-dump"), tempDir.resolve("case-images"), null))
                .hasMessageContaining("duplicadas");
    }

    @Test
    void validatesEveryStagingTreeBeforeMovingAnActiveTree() throws Exception {
        var activeImages = tempDir.resolve("active-before-promotion");
        Files.createDirectories(activeImages);
        Files.writeString(activeImages.resolve("keep.txt"), "keep");

        assertThatThrownBy(() -> service.replaceTrees(
                tempDir.resolve("missing-stage"), activeImages, null, null))
                .hasMessageContaining("staging");
        assertThat(Files.readString(activeImages.resolve("keep.txt"))).isEqualTo("keep");
    }

    @Test
    void failureBeforeMovingOriginalImagesNeverDeletesTheActiveTree() throws Exception {
        var activeImages = tree("active-images-first-move", "current.txt", "current-images");
        var stagedImages = tree("staged-images-first-move", "next.txt", "next-images");
        var failingService = serviceFailingMoveFrom(activeImages);

        assertThatThrownBy(() -> failingService.replaceTrees(
                stagedImages, activeImages, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fallo inyectado");

        assertThat(Files.readString(activeImages.resolve("current.txt"))).isEqualTo("current-images");
        assertThat(activeImages.resolve("next.txt")).doesNotExist();
        assertThat(Files.readString(stagedImages.resolve("next.txt"))).isEqualTo("next-images");
    }

    @Test
    void failurePromotingImagesRestoresTheMovedOriginalTree() throws Exception {
        var activeImages = tree("active-images-promotion", "current.txt", "current-images");
        var stagedImages = tree("staged-images-promotion", "next.txt", "next-images");
        var failingService = serviceFailingMoveFrom(stagedImages);

        assertThatThrownBy(() -> failingService.replaceTrees(
                stagedImages, activeImages, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fallo inyectado");

        assertThat(Files.readString(activeImages.resolve("current.txt"))).isEqualTo("current-images");
        assertThat(activeImages.resolve("next.txt")).doesNotExist();
    }

    @Test
    void failureBeforeMovingOriginalTemplatesRestoresImagesAndKeepsTemplatesUntouched() throws Exception {
        var activeImages = tree("active-images-template-move", "current.txt", "current-images");
        var stagedImages = tree("staged-images-template-move", "next.txt", "next-images");
        var activeTemplates = tree("active-templates-first-move", "current.txt", "current-templates");
        var stagedTemplates = tree("staged-templates-first-move", "next.txt", "next-templates");
        var failingService = serviceFailingMoveFrom(activeTemplates);

        assertThatThrownBy(() -> failingService.replaceTrees(
                stagedImages, activeImages, stagedTemplates, activeTemplates))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fallo inyectado");

        assertThat(Files.readString(activeImages.resolve("current.txt"))).isEqualTo("current-images");
        assertThat(activeImages.resolve("next.txt")).doesNotExist();
        assertThat(Files.readString(activeTemplates.resolve("current.txt"))).isEqualTo("current-templates");
        assertThat(activeTemplates.resolve("next.txt")).doesNotExist();
        assertThat(Files.readString(stagedTemplates.resolve("next.txt"))).isEqualTo("next-templates");
    }

    @Test
    void failurePromotingTemplatesRestoresBothMovedOriginalTrees() throws Exception {
        var activeImages = tree("active-images-template-promotion", "current.txt", "current-images");
        var stagedImages = tree("staged-images-template-promotion", "next.txt", "next-images");
        var activeTemplates = tree("active-templates-promotion", "current.txt", "current-templates");
        var stagedTemplates = tree("staged-templates-promotion", "next.txt", "next-templates");
        var failingService = serviceFailingMoveFrom(stagedTemplates);

        assertThatThrownBy(() -> failingService.replaceTrees(
                stagedImages, activeImages, stagedTemplates, activeTemplates))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fallo inyectado");

        assertThat(Files.readString(activeImages.resolve("current.txt"))).isEqualTo("current-images");
        assertThat(activeImages.resolve("next.txt")).doesNotExist();
        assertThat(Files.readString(activeTemplates.resolve("current.txt"))).isEqualTo("current-templates");
        assertThat(activeTemplates.resolve("next.txt")).doesNotExist();
    }

    @Test
    void refusesToCreateArchiveInsideIncludedTree() throws Exception {
        var dump = tempDir.resolve("inside-tree.backup");
        var images = tempDir.resolve("inside-images");
        Files.createDirectories(images);
        Files.writeString(dump, "dump");

        assertThatThrownBy(() -> service.create(dump, images, images.resolve("backup.zip")))
                .hasMessageContaining("dentro de un arbol");
    }

    @Test
    void rejectsSymbolicLinksWhenCreatingArchive() throws Exception {
        var images = tempDir.resolve("images-with-link");
        var outside = tempDir.resolve("outside-secret.txt");
        Files.createDirectories(images);
        Files.writeString(tempDir.resolve("database.backup"), "dump");
        Files.writeString(outside, "secret");
        try {
            Files.createSymbolicLink(images.resolve("leak.txt"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            Assumptions.assumeTrue(false, "El sistema no permite crear symlinks en esta prueba");
        }
        assertThatThrownBy(() -> service.create(
                tempDir.resolve("database.backup"), images, tempDir.resolve("symlink.zip")))
                .hasMessageContaining("enlace simbolico");
    }

    private static java.util.List<String> entries(Path archive) throws Exception {
        var entries = new ArrayList<String>();
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    private Path tree(String directory, String fileName, String contents) throws Exception {
        Path root = tempDir.resolve(directory);
        Files.createDirectories(root);
        Files.writeString(root.resolve(fileName), contents);
        return root;
    }

    private static BackupArchiveService serviceFailingMoveFrom(Path failingSource) {
        Path normalizedFailure = failingSource.toAbsolutePath().normalize();
        return new BackupArchiveService((source, destination) -> {
            if (source.equals(normalizedFailure)) {
                throw new IOException("fallo inyectado antes del move");
            }
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        });
    }
}

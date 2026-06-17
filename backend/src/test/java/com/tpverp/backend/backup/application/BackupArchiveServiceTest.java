package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupArchiveServiceTest {

    @TempDir
    private Path tempDir;

    private final BackupArchiveService service = new BackupArchiveService();

    @Test
    void packsDatabaseDumpAndProductImagesAndRestoresBoth() throws Exception {
        Path dump = tempDir.resolve("database.backup");
        Path images = tempDir.resolve("images");
        Path image = images.resolve("store/product/image.webp");
        Path archive = tempDir.resolve("backup.zip");
        Path restoredDump = tempDir.resolve("restored.backup");
        Path restoredImages = tempDir.resolve("restored-images");
        Files.createDirectories(image.getParent());
        Files.writeString(dump, "pg dump");
        Files.write(image, new byte[] {1, 2, 3});

        var info = service.create(dump, images, archive);
        service.restore(archive, restoredDump, restoredImages);

        assertThat(info.databaseBytes()).isEqualTo(7);
        assertThat(info.imageFiles()).isEqualTo(1);
        assertThat(Files.readString(restoredDump)).isEqualTo("pg dump");
        assertThat(Files.readAllBytes(restoredImages.resolve("store/product/image.webp")))
                .containsExactly(1, 2, 3);
    }
}

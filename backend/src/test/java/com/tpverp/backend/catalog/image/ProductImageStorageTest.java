package com.tpverp.backend.catalog.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductImageStorageTest {

    @TempDir
    Path directory;

    @Test
    void storesReadsAndDeletesBothVariants() throws Exception {
        var storage = new ProductImageStorage(directory);
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();

        storage.write(storeId, productId, imageId, new byte[] {1, 2}, new byte[] {3, 4});

        assertThat(storage.read(storeId, productId, imageId, false)).containsExactly(1, 2);
        assertThat(storage.read(storeId, productId, imageId, true)).containsExactly(3, 4);
        storage.delete(storeId, productId, imageId);
        assertThat(storage.exists(storeId, productId, imageId)).isFalse();
    }

    @Test
    void failedSecondMoveRemovesOnlyTheNewImage() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        var normal = new ProductImageStorage(directory);
        normal.write(storeId, productId, oldId, new byte[] {1}, new byte[] {2});
        var failing = new ProductImageStorage(directory, new FailingSecondMover());

        assertThatThrownBy(() -> failing.write(
                        storeId, productId, newId, new byte[] {3}, new byte[] {4}))
                .isInstanceOf(IOException.class);

        assertThat(normal.read(storeId, productId, oldId, false)).containsExactly(1);
        assertThat(normal.exists(storeId, productId, newId)).isFalse();
    }

    private static final class FailingSecondMover implements ProductImageStorage.AtomicFileMover {

        private int moves;

        @Override
        public void move(Path source, Path target) throws IOException {
            if (++moves == 2) {
                throw new IOException("fallo simulado");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentTemplateArtifactStorageTest {

    @TempDir Path tempDir;

    @Test
    void atomicallyStoresReadsAndDeletesSourceAndCompiledArtifact() throws Exception {
        var storage = new DocumentTemplateArtifactStorage(tempDir);
        var templateId = UUID.randomUUID();

        var stored = storage.write(templateId, new byte[] {1, 2}, new byte[] {3, 4});

        assertThat(stored.reference()).isEqualTo(templateId.toString());
        assertThat(storage.readSource(stored.reference())).containsExactly(1, 2);
        assertThat(storage.readCompiled(stored.reference())).containsExactly(3, 4);

        storage.delete(stored.reference());
        assertThat(Files.exists(tempDir.resolve(templateId.toString()))).isFalse();
    }

    @Test
    void rejectsReferencesThatAreNotInternalTemplateIdentifiers() {
        var storage = new DocumentTemplateArtifactStorage(tempDir);

        assertThatThrownBy(() -> storage.readSource("../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_artifact_reference_invalid");
    }
}

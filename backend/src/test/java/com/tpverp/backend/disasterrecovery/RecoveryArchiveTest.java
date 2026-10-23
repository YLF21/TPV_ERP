package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import com.tpverp.backend.backup.application.BackupArchiveService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryArchiveTest {
    @TempDir Path root;
    @Test void portableEntryStaysEncryptedDuringExtractionAndLegacyArchivesRemainReadable()throws Exception {
        Path dump=root.resolve("dump"),archive=root.resolve("copy.zip");Files.writeString(dump,"database");
        var service=new BackupArchiveService();byte[] portable=RecoverySecretsCodec.seal(Map.of("identity.private",new byte[]{1,2}),new byte[32],UUID.randomUUID(),UUID.randomUUID());
        service.create(dump,null,null,archive,null,portable);
        assertThat(service.readPortableCapsule(archive)).isEqualTo(portable);
        service.extractToStaging(archive,root.resolve("restored/dump"),root.resolve("restored/images"),root.resolve("restored/templates"));
        assertThat(Files.readString(root.resolve("restored/dump"))).isEqualTo("database");
        assertThat(Files.exists(root.resolve("restored/recovery-secrets.bin"))).isFalse();
        service.create(dump,null,null,archive,null);
        service.extractToStaging(archive,root.resolve("legacy/dump"),root.resolve("legacy/images"),root.resolve("legacy/templates"));
        assertThatThrownBy(()->service.readPortableCapsule(archive)).hasMessageContaining("missing");
    }
}

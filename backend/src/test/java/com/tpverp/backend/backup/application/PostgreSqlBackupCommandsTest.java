package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostgreSqlBackupCommandsTest {
    @TempDir Path directory;

    @Test void missingToolReportsAnActionableErrorWithoutCredentials() {
        var commands = new PostgreSqlBackupCommands("jdbc:postgresql://localhost/unused", "test-user", "private-password",
                directory.resolve("missing-pg-dump").toString(), directory.resolve("missing-pg-restore").toString());
        assertThatThrownBy(() -> commands.dump(directory.resolve("database.backup"), "snapshot"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instalación").hasMessageContaining("permisos")
                .hasMessageNotContaining("private-password");
    }
}

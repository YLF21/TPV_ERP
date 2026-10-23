package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class BackupSnapshotServiceTest {

    @Test
    void releasesFilesystemGuardWhenDatabaseConnectionFails() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("connection unavailable"));
        var service = new BackupSnapshotService(source, Clock.systemUTC());
        assertThatThrownBy(() -> service.capture(mock(PostgreSqlBackupCommands.class), Path.of("unused.dump"),
                UUID.randomUUID(), "test")).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SQLException.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(() -> {
                try (var mutation = SharedFileSnapshotGuard.fileMutation()) {
                    return true;
                }
            }).get(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}

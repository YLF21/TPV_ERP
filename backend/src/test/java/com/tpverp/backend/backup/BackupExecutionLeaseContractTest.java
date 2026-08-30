package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class BackupExecutionLeaseContractTest {
    @Test
    void externalBackupWorkIsNotTransactionalAndHeartbeatUsesRequiresNew() throws Exception {
        assertThat(BackupService.class.getMethod("executeNow").getAnnotation(Transactional.class)).isNull();
        Method heartbeat = BackupExecutionLeaseWriter.class.getMethod(
                "heartbeat", java.util.UUID.class, java.util.UUID.class, java.time.Instant.class);
        assertThat(heartbeat.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}

package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class BackupServiceContractTest {

    @Test
    void restoreDoesNotHoldJpaTransactionWhilePgRestoreRuns() throws Exception {
        var method = BackupService.class.getMethod(
                "restore", Path.class, Path.class, String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }
}

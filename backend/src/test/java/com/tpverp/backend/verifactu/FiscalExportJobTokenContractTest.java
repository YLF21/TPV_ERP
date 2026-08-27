package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalExportJobTokenContractTest {
    @Test
    void claimProgressAndFailureCarryTheSameExecutionToken() throws Exception {
        assertThat(FiscalExportJobRepository.class.getMethod(
                "claimQueued", UUID.class, UUID.class, Instant.class)).isNotNull();
        assertThat(FiscalExportJobRepository.class.getMethod(
                "updateProgress", UUID.class, UUID.class, long.class, boolean.class,
                Instant.class)).isNotNull();
        assertThat(FiscalExportJobRepository.class.getMethod(
                "markFailedIfRunning", UUID.class, UUID.class, String.class,
                Instant.class)).isNotNull();
        assertThat(FiscalExportJobRepository.class.getMethod(
                "findByIdAndExecutionToken", UUID.class, UUID.class)).isNotNull();
        assertThat(FiscalExportJob.class.getMethod("getExecutionToken")).isNotNull();
    }

    @Test
    void completedPathIsolatedPerExecutionToken() throws Exception {
        var method = FiscalExportJobService.class.getDeclaredMethod("exportPath", UUID.class, UUID.class);
        method.setAccessible(true);
        var jobId = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var service = new FiscalExportJobService(null, null, null, null, null, null);

        var firstPath = (Path) method.invoke(service, jobId, first);
        var secondPath = (Path) method.invoke(service, jobId, second);

        assertThat(firstPath).isNotEqualTo(secondPath);
        assertThat(firstPath.getFileName().toString()).isEqualTo(jobId + "-" + first + ".zip");
        assertThat(secondPath.getFileName().toString()).isEqualTo(jobId + "-" + second + ".zip");
    }
}

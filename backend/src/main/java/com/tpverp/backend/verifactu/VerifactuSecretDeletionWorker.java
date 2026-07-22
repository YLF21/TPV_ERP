package com.tpverp.backend.verifactu;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class VerifactuSecretDeletionWorker {

    private static final int BATCH_SIZE = 20;
    private static final Logger LOGGER =
            LoggerFactory.getLogger(VerifactuSecretDeletionWorker.class);

    private final VerifactuSecretDeletionService deletions;
    private final VerifactuCertificateSecretStore secrets;

    VerifactuSecretDeletionWorker(
            VerifactuSecretDeletionService deletions,
            VerifactuCertificateSecretStore secrets) {
        this.deletions = deletions;
        this.secrets = secrets;
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.secret-deletion-delay-ms:60000}")
    public void tick() {
        runOnce();
    }

    int runOnce() {
        int completed = 0;
        var owner = UUID.randomUUID();
        for (var job : deletions.claim(owner, BATCH_SIZE)) {
            try {
                // deleteIfExists makes a retry after a crash safe.
                secrets.delete(job.secretPath());
                if (deletions.complete(job.id(), owner)) {
                    completed++;
                }
            } catch (RuntimeException failure) {
                try {
                    deletions.retry(job.id(), owner);
                } catch (RuntimeException persistenceFailure) {
                    // Do not log the protected path or the filesystem exception text.
                    LOGGER.error(
                            "No se pudo reprogramar el borrado protegido VeriFactu {}",
                            job.id());
                }
            }
        }
        return completed;
    }
}

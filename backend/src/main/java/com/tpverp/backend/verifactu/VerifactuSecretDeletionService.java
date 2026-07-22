package com.tpverp.backend.verifactu;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class VerifactuSecretDeletionService {

    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    private static final Duration MAX_RETRY = Duration.ofHours(24);

    private final VerifactuSecretDeletionJobRepository jobs;
    private final StoreRepository stores;
    private final AuditService audit;
    private final Clock clock;

    VerifactuSecretDeletionService(
            VerifactuSecretDeletionJobRepository jobs,
            StoreRepository stores,
            AuditService audit,
            Clock clock) {
        this.jobs = jobs;
        this.stores = stores;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    VerifactuSecretDeletionJob enqueue(
            UUID companyId,
            UUID certificateId,
            String secretPath,
            VerifactuSecretDeletionReason reason) {
        return jobs.findBySecretPath(secretPath).orElseGet(() -> jobs.save(
                VerifactuSecretDeletionJob.pending(
                        companyId, certificateId, secretPath, reason, clock.instant())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueAfterRollback(
            UUID companyId,
            String secretPath,
            VerifactuSecretDeletionReason reason) {
        enqueue(companyId, null, secretPath, reason);
    }

    @Transactional
    List<ClaimedSecretDeletion> claim(UUID owner, int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("El lote debe contener entre 1 y 100 borrados");
        }
        var now = clock.instant();
        var claimed = jobs.findClaimable(now, batchSize);
        claimed.forEach(job -> job.claim(owner, now, now.plus(LEASE)));
        jobs.saveAll(claimed);
        return claimed.stream().map(ClaimedSecretDeletion::from).toList();
    }

    @Transactional
    boolean complete(UUID jobId, UUID owner) {
        var job = jobs.findById(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            return false;
        }
        job.complete(owner, clock.instant());
        jobs.save(job);
        audit.recordSystem(
                firstStore(job.getCompanyId()),
                "VERIFACTU_CERTIFICATE_SECRET_DELETED",
                AuditResult.EXITO,
                auditDetails(job));
        return true;
    }

    @Transactional
    boolean retry(UUID jobId, UUID owner) {
        var job = jobs.findById(jobId).orElse(null);
        if (job == null || !job.isOwnedBy(owner)) {
            return false;
        }
        var now = clock.instant();
        var retryAt = now.plus(backoff(job.getAttempts()));
        job.retry(owner, retryAt, "SECRET_DELETE_FAILED");
        jobs.save(job);
        var details = auditDetails(job);
        details.put("nextAttemptAt", retryAt.toString());
        audit.recordSystem(
                firstStore(job.getCompanyId()),
                "VERIFACTU_CERTIFICATE_SECRET_DELETE_FAILED",
                AuditResult.FALLO,
                details);
        return true;
    }

    private com.tpverp.backend.organization.Store firstStore(UUID companyId) {
        return stores.findByEmpresaId(companyId).stream().findFirst().orElse(null);
    }

    private static LinkedHashMap<String, Object> auditDetails(
            VerifactuSecretDeletionJob job) {
        var details = new LinkedHashMap<String, Object>();
        if (job.getCertificateId() != null) {
            details.put("certificateId", job.getCertificateId());
        }
        details.put("reason", job.getReason().name());
        details.put("attempts", job.getAttempts());
        return details;
    }

    private static Duration backoff(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 10);
        var calculated = FIRST_RETRY.multipliedBy(1L << exponent);
        return calculated.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : calculated;
    }

    record ClaimedSecretDeletion(UUID id, String secretPath) {
        private static ClaimedSecretDeletion from(VerifactuSecretDeletionJob job) {
            return new ClaimedSecretDeletion(job.getId(), job.getSecretPath());
        }
    }
}

package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VerifactuSecretDeletionJobRepository
        extends JpaRepository<VerifactuSecretDeletionJob, UUID> {

    Optional<VerifactuSecretDeletionJob> findBySecretPath(String secretPath);

    @Query(value = """
            select job.*
              from verifactu_secret_deletion_job job
             where (job.status = 'PENDIENTE' and job.next_attempt_at <= :now)
                or (job.status = 'PROCESANDO' and job.processing_lease_until <= :now)
             order by coalesce(job.next_attempt_at, job.processing_lease_until), job.created_at, job.id
             for update skip locked
             limit :batchSize
            """, nativeQuery = true)
    List<VerifactuSecretDeletionJob> findClaimable(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);
}

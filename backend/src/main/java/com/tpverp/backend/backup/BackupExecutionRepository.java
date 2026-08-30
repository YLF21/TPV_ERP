package com.tpverp.backend.backup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BackupExecutionRepository extends JpaRepository<BackupExecution, UUID> {

    List<BackupExecution> findByConfiguracionIdOrderByIniciadaEnDesc(UUID configuracionId);

    List<BackupExecution> findTop100ByConfiguracionIdOrderByIniciadaEnDesc(UUID configuracionId);

    boolean existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
            UUID configuracionId, BackupResult resultado, Instant startedAt);

    @Modifying
    @Query(value = """
            update ejecucion_backup
               set result = 'FALLO', finalizada_en = :now,
                   heartbeat_at = :now, lease_until = :now,
                   error_reason = 'LEASE_EXPIRED'
             where configuracion_id = :configurationId
               and result = 'EN_CURSO'
               and (lease_until is null or lease_until < :now)
            """, nativeQuery = true)
    int expireStaleLeases(@Param("configurationId") UUID configurationId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            update ejecucion_backup
               set heartbeat_at = :now, lease_until = :leaseUntil
             where id = :id and result = 'EN_CURSO' and worker_token = :token
            """, nativeQuery = true)
    int heartbeat(@Param("id") UUID id, @Param("token") UUID token,
            @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    @Modifying
    @Query(value = """
            update ejecucion_backup
               set result = :result, finalizada_en = :finishedAt,
                   heartbeat_at = :finishedAt, lease_until = :finishedAt,
                   metadata = cast(:metadata as jsonb), error_reason = :errorReason
             where id = :id and result = 'EN_CURSO' and worker_token = :token
            """, nativeQuery = true)
    int finishLease(@Param("id") UUID id, @Param("token") UUID token,
            @Param("result") String result, @Param("finishedAt") Instant finishedAt,
            @Param("metadata") String metadata, @Param("errorReason") String errorReason);
}

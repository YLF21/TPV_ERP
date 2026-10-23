package com.tpverp.backend.backup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BackupExecutionRepository extends JpaRepository<BackupExecution, UUID> {

    @Query(value = """
            select exists(select 1 from ejecucion_backup e
              where e.result='EXITO' and e.metadata->>'cloudRequired'='true'
                and coalesce(e.metadata->>'restoreSourceUnavailable','false') <> 'true'
                and coalesce(e.metadata->>'cloudAlreadyStored','false') <> 'true'
                and (e.metadata->>'path'=:path or e.metadata->>'monthlyPath'=:path)
                and not exists(select 1 from cloud_backup_delivery q where q.backup_id=e.id))
            """, nativeQuery = true)
    boolean needsCloudSpool(@Param("path") String path);

    @Query(value = "select id from ejecucion_backup where metadata->>'path'=:path order by iniciada_en desc limit 1", nativeQuery = true)
    java.util.Optional<UUID> findIdByArtifactPath(@Param("path") String path);

    org.springframework.data.domain.Page<BackupExecution> findByConfiguracionIdOrderBySequenceNumberDesc(
            UUID configurationId, org.springframework.data.domain.Pageable pageable);

    java.util.Optional<BackupExecution> findTopByConfiguracionIdAndResultadoOrderByFinalizadaEnDesc(
            UUID configurationId, BackupResult resultado);

    boolean existsByConfiguracionIdAndResultado(UUID configurationId, BackupResult resultado);

    @Modifying
    @Query(value = "update ejecucion_backup set phase = :phase where id = :id and worker_token = :token and result = 'EN_CURSO'", nativeQuery = true)
    int progress(@Param("id") UUID id, @Param("token") UUID token, @Param("phase") String phase);

    List<BackupExecution> findByConfiguracionIdOrderByIniciadaEnDesc(UUID configuracionId);

    List<BackupExecution> findTop100ByConfiguracionIdOrderByIniciadaEnDesc(UUID configuracionId);

    boolean existsByConfiguracionIdAndResultadoAndIniciadaEnGreaterThanEqual(
            UUID configuracionId, BackupResult resultado, Instant startedAt);

    @Modifying
    @Query(value = """
            update ejecucion_backup
               set result = 'FALLO', finalizada_en = :now,
                   heartbeat_at = :now, lease_until = :now,
                   error_reason = 'LEASE_EXPIRED', phase = 'FAILED'
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
                   metadata = cast(:metadata as jsonb), error_reason = :errorReason,
                   phase = case when :result = 'EXITO' then 'COMPLETED' else 'FAILED' end
             where id = :id and result = 'EN_CURSO' and worker_token = :token
            """, nativeQuery = true)
    int finishLease(@Param("id") UUID id, @Param("token") UUID token,
            @Param("result") String result, @Param("finishedAt") Instant finishedAt,
            @Param("metadata") String metadata, @Param("errorReason") String errorReason);
}

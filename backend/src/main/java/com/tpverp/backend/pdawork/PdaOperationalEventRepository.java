package com.tpverp.backend.pdawork;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PdaOperationalEventRepository extends JpaRepository<PdaOperationalEvent, UUID> {
    Optional<PdaOperationalEvent> findByStoreIdAndTerminalIdAndClientEventId(UUID storeId, UUID terminalId, UUID clientEventId);

    @Modifying
    @Query(value = """
            insert into pda_evento_operativo
                (id, tienda_id, terminal_id, client_event_id, tipo, codigo, trabajo_id,
                 duracion_ms, ocurrido_en, recibido_en, trace_id, version)
            values (:id, :storeId, :terminalId, :clientEventId, :type, :code, :workId,
                    :durationMs, :occurredAt, :receivedAt, :traceId, 0)
            on conflict (tienda_id, terminal_id, client_event_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId, @Param("clientEventId") UUID clientEventId,
            @Param("type") String type, @Param("code") String code, @Param("workId") UUID workId,
            @Param("durationMs") Long durationMs, @Param("occurredAt") Instant occurredAt,
            @Param("receivedAt") Instant receivedAt, @Param("traceId") String traceId);
    Page<PdaOperationalEvent> findByStoreIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
            UUID storeId, Instant occurredAt, Pageable pageable);
    Page<PdaOperationalEvent> findByStoreIdAndTerminalIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
            UUID storeId, UUID terminalId, Instant occurredAt, Pageable pageable);
    long countByStoreIdAndTipoAndOccurredAtGreaterThanEqual(UUID storeId, PdaOperationalEventType type, Instant occurredAt);
    long countByStoreIdAndTerminalIdAndTipoAndOccurredAtGreaterThanEqual(
            UUID storeId, UUID terminalId, PdaOperationalEventType type, Instant occurredAt);
    List<PdaOperationalEvent> findByStoreIdAndTipoInAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
            UUID storeId, Collection<PdaOperationalEventType> types, Instant occurredAt, Pageable pageable);
    List<PdaOperationalEvent> findByStoreIdAndTerminalIdAndTipoInAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(
            UUID storeId, UUID terminalId, Collection<PdaOperationalEventType> types, Instant occurredAt, Pageable pageable);

    @Query(value = """
            select count(*) as sampleCount,
                   coalesce(avg(duracion_ms),0) as averageLatencyMs,
                   coalesce(percentile_cont(0.95) within group (order by duracion_ms),0) as p95LatencyMs,
                   coalesce(max(duracion_ms),0) as maxLatencyMs
              from pda_evento_operativo
             where tienda_id=:storeId and tipo='REQUEST_LATENCY' and ocurrido_en>=:from
            """, nativeQuery = true)
    LatencyAggregate latency(@Param("storeId") UUID storeId, @Param("from") Instant from);

    @Query(value = """
            select count(*) as sampleCount,
                   coalesce(avg(duracion_ms),0) as averageLatencyMs,
                   coalesce(percentile_cont(0.95) within group (order by duracion_ms),0) as p95LatencyMs,
                   coalesce(max(duracion_ms),0) as maxLatencyMs
              from pda_evento_operativo
             where tienda_id=:storeId and terminal_id=:terminalId
               and tipo='REQUEST_LATENCY' and ocurrido_en>=:from
            """, nativeQuery = true)
    LatencyAggregate latencyForTerminal(@Param("storeId") UUID storeId, @Param("terminalId") UUID terminalId,
            @Param("from") Instant from);

    @Modifying
    @Query(value = """
            delete from pda_evento_operativo
             where id in (
                 select id from pda_evento_operativo
                  where recibido_en < :cutoff
                  order by recibido_en
                  limit :batchSize
             )
            """, nativeQuery = true)
    int deleteRetentionBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    interface LatencyAggregate {
        long getSampleCount();
        double getAverageLatencyMs();
        double getP95LatencyMs();
        long getMaxLatencyMs();
    }
}

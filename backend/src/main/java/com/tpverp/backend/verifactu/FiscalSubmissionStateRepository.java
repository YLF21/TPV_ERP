package com.tpverp.backend.verifactu;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Pageable;

public interface FiscalSubmissionStateRepository
        extends JpaRepository<FiscalSubmissionState, UUID> {

    @Query(value = """
            select exists (
                select 1
                  from registro_fiscal target
                 where target.id = :recordId
                   and exists (
                    select 1
                      from registro_fiscal predecessor
                      left join estado_envio_fiscal predecessor_state
                        on predecessor_state.registro_id = predecessor.id
                     where predecessor.modo_fiscal = 'VERIFACTU'
                       and predecessor.cadena_id = target.cadena_id
                       and predecessor.secuencia < target.secuencia
                       and (predecessor_state.registro_id is null
                            or (predecessor_state.estado not in
                               ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'SUBSANADO')
                                and not ((predecessor_state.estado in ('PENDIENTE', 'ENVIADO')
                                          and coalesce(predecessor_state.proximo_intento_en,
                                                       predecessor_state.actualizado_en) <= :now)
                                         or (predecessor_state.estado = 'ENVIANDO'
                                             and predecessor_state.lease_hasta <= :now))))))
            )
            """, nativeQuery = true)
    boolean hasBlockingPredecessor(
            @Param("recordId") UUID recordId, @Param("now") Instant now);

    /** Manual retry cannot rely on batch eligibility: every prior line blocks it. */
    @Query(value = """
            select exists (
                select 1
                  from registro_fiscal target
                 where target.id = :recordId
                   and exists (
                    select 1
                      from registro_fiscal predecessor
                      left join estado_envio_fiscal predecessor_state
                        on predecessor_state.registro_id = predecessor.id
                     where predecessor.modo_fiscal = 'VERIFACTU'
                       and predecessor.cadena_id = target.cadena_id
                       and predecessor.secuencia < target.secuencia
                       and (predecessor_state.registro_id is null
                            or predecessor_state.estado not in
                               ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'SUBSANADO')))
            )
            """, nativeQuery = true)
    boolean hasAnyNonTerminalPredecessor(@Param("recordId") UUID recordId);

    /**
     * Atomic candidate selection for the submission worker.  The predecessor
     * predicate is what preserves fiscal chain order while allowing unrelated
     * installations to be claimed by different workers. RECHAZADO is an AEAT
     * terminal response: its correction is a new record in the same chain;
     * local DEFECTUOSO remains blocking until an explicit administrative decision.
     */
    @Query(value = """
            select state.*
              from estado_envio_fiscal state
              join registro_fiscal record on record.id = state.registro_id
             where record.modo_fiscal = 'VERIFACTU'
               and ((state.estado in ('PENDIENTE', 'ENVIADO')
                     and coalesce(state.proximo_intento_en, state.actualizado_en) <= :now)
                    or (state.estado = 'ENVIANDO'
                        and state.lease_hasta <= :now))
               and not exists (
                    select 1
                      from registro_fiscal predecessor
                      left join estado_envio_fiscal predecessor_state
                        on predecessor_state.registro_id = predecessor.id
                     where predecessor.modo_fiscal = 'VERIFACTU'
                             and predecessor.cadena_id = record.cadena_id
                             and predecessor.secuencia < record.secuencia
                       and (predecessor_state.registro_id is null
                            or (predecessor_state.estado not in
                           ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'SUBSANADO')
                            and not ((predecessor_state.estado in ('PENDIENTE', 'ENVIADO')
                                      and coalesce(predecessor_state.proximo_intento_en, predecessor_state.actualizado_en) <= :now)
                                     or (predecessor_state.estado = 'ENVIANDO' and predecessor_state.lease_hasta <= :now)))))
             order by record.instalacion_id, record.secuencia, record.id
             for update of state skip locked
             limit :batchSize
            """, nativeQuery = true)
    List<FiscalSubmissionState> findClaimable(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    /** Candidate discovery only; the actual claim is locked after scope lock. */
    @Query(value = """
            select candidate.*
              from (
                    select state.*,
                           record.empresa_id as scope_company_id,
                           record.instalacion_id as scope_installation_id,
                           artifact.entorno as scope_environment,
                           flow.ultimo_envio_en as scope_last_served,
                           flow.siguiente_envio_en as scope_next_allowed,
                           flow.lease_hasta as scope_lease_until,
                           row_number() over (
                               partition by record.empresa_id, record.instalacion_id, artifact.entorno
                               order by record.cadena_id, record.secuencia, record.id) as scope_rank
                      from estado_envio_fiscal state
                      join registro_fiscal record on record.id = state.registro_id
                      join artefacto_registro_fiscal artifact on artifact.registro_id = record.id
                      left join flujo_envio_fiscal_scope flow
                        on flow.empresa_id = record.empresa_id
                       and flow.instalacion_id = record.instalacion_id
                       and flow.entorno = artifact.entorno
                     where record.modo_fiscal = 'VERIFACTU'
                       and ((state.estado in ('PENDIENTE', 'ENVIADO')
                             and coalesce(state.proximo_intento_en, state.actualizado_en) <= :now)
                            or (state.estado = 'ENVIANDO' and state.lease_hasta <= :now))
                       and not exists (
                            select 1 from registro_fiscal predecessor
                            left join estado_envio_fiscal predecessor_state
                              on predecessor_state.registro_id = predecessor.id
                           where predecessor.modo_fiscal = 'VERIFACTU'
                             and predecessor.cadena_id = record.cadena_id
                             and predecessor.secuencia < record.secuencia
                             and (predecessor_state.registro_id is null
                                  or (predecessor_state.estado not in
                                     ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'SUBSANADO')
                                   and not ((predecessor_state.estado in ('PENDIENTE', 'ENVIADO')
                                             and coalesce(predecessor_state.proximo_intento_en, predecessor_state.actualizado_en) <= :now)
                                            or (predecessor_state.estado = 'ENVIANDO' and predecessor_state.lease_hasta <= :now)))))
                   ) candidate
             where candidate.scope_rank = 1
              -- Always try unpaced/unleased scopes first. A paced scope is
              -- retained as a fallback so a full 1000-record bypass can still
              -- be selected, but it must not starve scopes that are ready.
              order by case when (candidate.scope_next_allowed is null
                                   or candidate.scope_next_allowed <= :now)
                                  and (candidate.scope_lease_until is null
                                       or candidate.scope_lease_until <= :now)
                            then 0 else 1 end,
                       candidate.scope_last_served nulls first,
                      candidate.scope_last_served,
                      candidate.scope_company_id, candidate.scope_installation_id,
                      candidate.scope_environment
             limit :batchSize
            """, nativeQuery = true)
    List<FiscalSubmissionState> findClaimableDiscovery(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    @Query(value = """
            select state.*
              from estado_envio_fiscal state
              join registro_fiscal record on record.id = state.registro_id
              join artefacto_registro_fiscal artifact on artifact.registro_id = record.id
             where record.empresa_id = :companyId
               and record.instalacion_id = :installationId
               and artifact.entorno = :environment
               and record.modo_fiscal = 'VERIFACTU'
               and ((state.estado in ('PENDIENTE', 'ENVIADO')
                     and coalesce(state.proximo_intento_en, state.actualizado_en) <= :now)
                    or (state.estado = 'ENVIANDO' and state.lease_hasta <= :now))
               and not exists (
                    select 1 from registro_fiscal predecessor
                    left join estado_envio_fiscal predecessor_state
                      on predecessor_state.registro_id = predecessor.id
                   where predecessor.modo_fiscal = 'VERIFACTU'
                             and predecessor.cadena_id = record.cadena_id
                             and predecessor.secuencia < record.secuencia
                     and (predecessor_state.registro_id is null
                          or (predecessor_state.estado not in
                             ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'SUBSANADO')
                           and not ((predecessor_state.estado in ('PENDIENTE', 'ENVIADO')
                                     and coalesce(predecessor_state.proximo_intento_en, predecessor_state.actualizado_en) <= :now)
                                    or (predecessor_state.estado = 'ENVIANDO' and predecessor_state.lease_hasta <= :now)))))
             order by record.cadena_id, record.secuencia, record.id
             for update of state skip locked
             limit :batchSize
            """, nativeQuery = true)
    List<FiscalSubmissionState> findClaimableBatch(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("environment") String environment,
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    @Query(value = """
            select count(*)
              from estado_envio_fiscal state
              join registro_fiscal record on record.id = state.registro_id
              join artefacto_registro_fiscal artifact on artifact.registro_id = record.id
             where record.empresa_id = :companyId
               and record.instalacion_id = :installationId
               and artifact.entorno = :environment
               and record.modo_fiscal = 'VERIFACTU'
               and ((state.estado in ('PENDIENTE', 'ENVIADO')
                     and coalesce(state.proximo_intento_en, state.actualizado_en) <= :now)
                    or (state.estado = 'ENVIANDO' and state.lease_hasta <= :now))
               and not exists (
                    select 1 from registro_fiscal predecessor
                    left join estado_envio_fiscal predecessor_state
                      on predecessor_state.registro_id = predecessor.id
                   where predecessor.modo_fiscal = 'VERIFACTU'
                             and predecessor.cadena_id = record.cadena_id
                             and predecessor.secuencia < record.secuencia
                     and (predecessor_state.registro_id is null
                          or (predecessor_state.estado not in
                             ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'SUBSANADO')
                           and not ((predecessor_state.estado in ('PENDIENTE', 'ENVIADO')
                                     and coalesce(predecessor_state.proximo_intento_en, predecessor_state.actualizado_en) <= :now)
                                    or (predecessor_state.estado = 'ENVIANDO' and predecessor_state.lease_hasta <= :now)))))
            """, nativeQuery = true)
    long countClaimableBatch(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("environment") String environment,
            @Param("now") Instant now);

    /**
     * Manual AEAT TEST dispatch is explicitly scoped and only opens the first
     * pending record in the fiscal chain.  The predecessor predicate keeps a
     * manual dispatch from overtaking an earlier pending record while allowing
     * the new record created to correct an AEAT rejection.
     */
    @Query(value = """
            select state.*
              from estado_envio_fiscal state
              join registro_fiscal record on record.id = state.registro_id
             where record.empresa_id = :companyId
               and record.instalacion_id = :installationId
               and record.modo_fiscal = 'VERIFACTU'
               and state.estado = 'PENDIENTE'
               and coalesce(state.proximo_intento_en, state.actualizado_en) <= :now
               and not exists (
                    select 1
                      from registro_fiscal predecessor
                      left join estado_envio_fiscal predecessor_state
                        on predecessor_state.registro_id = predecessor.id
                   where predecessor.modo_fiscal = 'VERIFACTU'
                             and predecessor.cadena_id = record.cadena_id
                             and predecessor.secuencia < record.secuencia
                     and (predecessor_state.registro_id is null
                            or (predecessor_state.estado not in
                           ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'SUBSANADO')
                            and not ((predecessor_state.estado in ('PENDIENTE', 'ENVIADO')
                                      and coalesce(predecessor_state.proximo_intento_en, predecessor_state.actualizado_en) <= :now)
                                     or (predecessor_state.estado = 'ENVIANDO' and predecessor_state.lease_hasta <= :now)))))
             order by record.secuencia asc, record.id asc
             for update of state skip locked
             limit :batchSize
            """, nativeQuery = true)
    List<FiscalSubmissionState> findPendingClaimableForScope(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    /** Non-locking scope discovery used before acquiring the durable scope lock. */
    @Query(value = """
            select distinct artifact.entorno
              from estado_envio_fiscal state
              join registro_fiscal record on record.id = state.registro_id
              join artefacto_registro_fiscal artifact on artifact.registro_id = record.id
             where record.empresa_id = :companyId
               and record.instalacion_id = :installationId
               and record.modo_fiscal = 'VERIFACTU'
               and ((state.estado in ('PENDIENTE', 'ENVIADO')
                     and coalesce(state.proximo_intento_en, state.actualizado_en) <= :now)
                    or (state.estado = 'ENVIANDO' and state.lease_hasta <= :now))
             order by artifact.entorno
            """, nativeQuery = true)
    List<String> findClaimableEnvironmentsForScope(
            @Param("companyId") UUID companyId,
            @Param("installationId") UUID installationId,
            @Param("now") Instant now);

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtDesc(
            Collection<FiscalSubmissionStatus> statuses);

    /**
     * Defect list projection scoped before joining, avoiding a global state load
     * followed by one record query per state. The pageable is a hard database
     * limit and the projection never selects registro_fiscal.snapshot.
     */
    @Query("""
            select new com.tpverp.backend.verifactu.DefectiveFiscalRecordView(
                state.recordId,
                record.documentId,
                state.status,
                record.operation,
                record.documentType,
                record.number,
                record.issueDate,
                record.generatedAt,
                record.totalAmount,
                snapshot.qrUrl,
                state.lastErrorCode,
                state.lastError,
                state.updatedAt)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            left join FiscalPrintSnapshotRecord snapshot on snapshot.recordId = record.id
            where record.companyId = :companyId
              and record.storeId = :storeId
              and record.installationId = :installationId
              and state.status in :statuses
            order by state.updatedAt desc, record.sequence desc, record.id desc
            """)
    List<DefectiveFiscalRecordView> findDefectiveViews(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("installationId") UUID installationId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses,
            Pageable pageable);

    List<FiscalSubmissionState> findAllByStatusInOrderByUpdatedAtAsc(
            Collection<FiscalSubmissionStatus> statuses);

    /**
     * Bounded administrative queue projection.  The record scope and fiscal
     * mode predicates are pushed into SQL so the legacy list endpoint never
     * loads all state rows and then performs one record lookup per row.
     */
    @Query("""
            select new com.tpverp.backend.verifactu.FiscalSubmissionQueueItem(
                record.id,
                record.sequence,
                state.status,
                record.operation,
                record.documentType,
                record.number,
                state.lastErrorCode,
                state.lastError,
                state.updatedAt)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            where record.companyId = :companyId
              and record.storeId = :storeId
              and record.fiscalMode = com.tpverp.backend.verifactu.FiscalMode.VERIFACTU
              and state.status in :statuses
            order by state.updatedAt asc, record.sequence asc, record.id asc
            """)
    List<FiscalSubmissionQueueItem> findAdminQueueItems(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses,
            Pageable pageable);

    @Query("""
            select new com.tpverp.backend.verifactu.FiscalSubmissionQueueItem(
                record.id,
                record.sequence,
                state.status,
                record.operation,
                record.documentType,
                record.number,
                state.lastErrorCode,
                state.lastError,
                state.updatedAt)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            where record.companyId = :companyId
              and record.storeId = :storeId
              and record.installationId = :installationId
              and record.fiscalMode = com.tpverp.backend.verifactu.FiscalMode.VERIFACTU
              and state.status in :statuses
            order by state.updatedAt asc, record.sequence asc, record.id asc
            """)
    List<FiscalSubmissionQueueItem> findAdminQueueItems(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("installationId") UUID installationId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from FiscalSubmissionState state where state.recordId = :recordId")
    Optional<FiscalSubmissionState> findForUpdate(@Param("recordId") UUID recordId);

    @Query("""
            select new com.tpverp.backend.verifactu.VerifactuPosQueueRecord(
                record.number,
                record.documentType,
                state.status,
                state.updatedAt)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            join CommercialDocument commercialDocument
                on commercialDocument.id = record.documentId
            where record.companyId = :companyId
              and record.storeId = :storeId
              and commercialDocument.tiendaId = :storeId
              and commercialDocument.terminalOrigenId = :terminalId
              and state.status in :statuses
            order by state.updatedAt desc, record.sequence desc, record.id desc
            """)
    List<VerifactuPosQueueRecord> findPosQueue(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses,
            Pageable pageable);

    @Query("""
            select count(state)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            join CommercialDocument commercialDocument
                on commercialDocument.id = record.documentId
            where record.companyId = :companyId
              and record.storeId = :storeId
              and commercialDocument.tiendaId = :storeId
              and commercialDocument.terminalOrigenId = :terminalId
              and state.status in :statuses
            """)
    long countPosQueueByStatusIn(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses);

    @Query("""
            select count(state)
            from FiscalSubmissionState state
            join FiscalRecord record on record.id = state.recordId
            where record.companyId = :companyId
              and state.status in :statuses
            """)
    long countByCompanyIdAndStatusIn(
            @Param("companyId") UUID companyId,
            @Param("statuses") Collection<FiscalSubmissionStatus> statuses);
}

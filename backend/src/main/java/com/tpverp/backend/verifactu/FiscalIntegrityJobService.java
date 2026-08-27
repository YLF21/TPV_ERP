package com.tpverp.backend.verifactu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, context-free coordinator for long-running integrity scans. */
@Service
public class FiscalIntegrityJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FiscalIntegrityJobService.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final int MAX_EVIDENCE = 1_000;
    private static final List<FiscalIntegrityJobStatus> ACTIVE =
            List.of(FiscalIntegrityJobStatus.QUEUED, FiscalIntegrityJobStatus.RUNNING);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalRecordRepository records;
    private final FiscalEventRepository events;
    private final FiscalIntegrityJobRepository jobs;
    private final FiscalIntegrityService integrity;
    private final NamedParameterJdbcTemplate jdbc;
    private final FiscalRuntimeProperties runtime;

    public FiscalIntegrityJobService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            VerifactuConfigurationRepository configurations,
            FiscalRecordRepository records, FiscalEventRepository events,
            FiscalIntegrityJobRepository jobs, FiscalIntegrityService integrity,
            NamedParameterJdbcTemplate jdbc, FiscalRuntimeProperties runtime) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.configurations = configurations;
        this.records = records;
        this.events = events;
        this.jobs = jobs;
        this.integrity = integrity;
        this.jdbc = jdbc;
        this.runtime = runtime;
    }

    @Transactional
    public FiscalIntegrityJobView create(String requestedBy) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        if (store == null || company == null || store.getEmpresa() == null
                || !company.getId().equals(store.getEmpresa().getId())) {
            throw new IllegalStateException("El contexto fiscal actual no es valido");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var owner = requireOwner(requestedBy);
        lockAdmission(company.getId(), installation.getId());
        var initialMode = runtime.isSandbox() ? runtime.sandboxInitialMode() : FiscalMode.PRE_SIF;
        configurations.insertIfMissingWithMode(UUID.randomUUID(), company.getId(), initialMode.name());
        var mode = configurations.findForUpdateByCompanyId(company.getId())
                .map(VerifactuConfiguration::getCurrentMode)
                .orElseThrow(() -> new IllegalStateException("Configuracion fiscal no encontrada"));
        if (jobs.countByCompanyIdAndInstallationIdAndStatusIn(
                company.getId(), installation.getId(), ACTIVE) > 0) {
            throw new IllegalStateException("fiscal_integrity_active_limit");
        }
        var billing = records.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installation.getId()).map(FiscalRecord::getSequence).orElse(0L);
        var event = events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                company.getId(), installation.getId()).map(FiscalEvent::getSequence).orElse(0L);
        try {
            return view(jobs.save(new FiscalIntegrityJob(company.getId(), store.getId(),
                    installation.getId(), owner, mode, billing, event, Instant.now())));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("fiscal_integrity_active_limit", duplicate);
        }
    }

    public Page<FiscalIntegrityJobView> list(int page, int size, String requestedBy, boolean admin) {
        if (page < 0) throw new IllegalArgumentException("fiscal_integrity_page_invalid");
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var bounded = PageRequest.of(page, Math.max(1, Math.min(size, 50)));
        var result = admin
                ? jobs.findAllByCompanyIdAndStoreIdAndInstallationIdOrderByCreatedAtDesc(
                        company.getId(), store.getId(), installation.getId(), bounded)
                : jobs.findAllByCompanyIdAndStoreIdAndInstallationIdAndRequestedByOrderByCreatedAtDesc(
                        company.getId(), store.getId(), installation.getId(), requireOwner(requestedBy), bounded);
        return result.map(FiscalIntegrityJobView::from);
    }

    public FiscalIntegrityJobView status(UUID id, String requestedBy, boolean admin) {
        return FiscalIntegrityJobView.from(authorized(id, requestedBy, admin));
    }

    @Transactional
    public FiscalIntegrityJobView retry(UUID id, String requestedBy, boolean admin) {
        var previous = authorized(id, requestedBy, admin);
        if (previous.getStatus() == FiscalIntegrityJobStatus.QUEUED
                || previous.getStatus() == FiscalIntegrityJobStatus.RUNNING) {
            throw new IllegalStateException("fiscal_integrity_retry_not_allowed");
        }
        var result = create(requestedBy);
        return result;
    }

    /** Claims first, then reads the job from a fresh transaction-free context. */
    public void run(UUID id) {
        var now = Instant.now();
        var executionToken = UUID.randomUUID();
        if (jobs.claimQueued(id, executionToken, now) == 0) return;
        try {
            var job = jobs.findById(id).orElseThrow(
                    () -> new IllegalStateException("fiscal_integrity_job_not_found"));
            var result = integrity.checkSnapshot(job.getCompanyId(), job.getInstallationId(),
                    job.getExecutionMode(), job.getBillingSnapshotSequence(),
                    job.getEventSnapshotSequence(),
                    (billing, event, total, billingAnomalies, eventAnomalies, anomalies) ->
                            persistProgressIfOwned(job.getId(), executionToken, billing, event, total,
                                    billingAnomalies, eventAnomalies, anomalies));
            var evidence = evidenceJson(result.anomalies());
            if (jobs.markCompleted(job.getId(), executionToken,
                    result.billingRecordsChecked(), result.eventRecordsChecked(),
                    result.anomaliesTotal(), result.billingAnomalies(), result.eventAnomalies(), evidence,
                    Instant.now()) != 1) {
                throw new IllegalStateException("fiscal_integrity_claim_lost");
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Fiscal integrity job {} failed", id, failure);
            jobs.markFailedIfRunning(id, executionToken, "fiscal_integrity_failed", Instant.now());
        }
    }

    @Transactional
    public int requeueStaleJobs() {
        var now = Instant.now();
        return jobs.requeueStaleJobs(now, now.minus(STALE_AFTER));
    }

    /**
     * Atomically requeues each stale candidate and returns only rows actually
     * recovered. The lifecycle interrupts local workers after ownership is
     * revoked; remote workers stop on their token-guarded updates.
     */
    @Transactional
    public List<UUID> recoverStaleJobs() {
        var now = Instant.now();
        var staleBefore = now.minus(STALE_AFTER);
        var candidates = jobs.findStaleRunningIds(staleBefore);
        if (candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream()
                .filter(id -> jobs.requeueRunningJob(id, now, staleBefore) == 1)
                .toList();
    }

    public List<UUID> queuedIds() {
        return jobs.findTop100ByStatusOrderByCreatedAtAsc(FiscalIntegrityJobStatus.QUEUED)
                .stream().map(FiscalIntegrityJob::getId).toList();
    }

    void persistProgress(UUID id, UUID executionToken, long billing, long event, long total,
            long billingAnomalies, long eventAnomalies, List<String> anomalies) {
        persistProgressIfOwned(id, executionToken, billing, event, total,
                billingAnomalies, eventAnomalies, anomalies);
    }

    private void persistProgressIfOwned(UUID id, UUID executionToken, long billing, long event,
            long total, long billingAnomalies, long eventAnomalies, List<String> anomalies) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("fiscal_integrity_worker_cancelled");
        }
        var bounded = anomalies == null ? List.<String>of() : anomalies.stream().limit(MAX_EVIDENCE).toList();
        if (jobs.updateProgress(id, executionToken, billing, event, total,
                billingAnomalies, eventAnomalies,
                evidenceJson(bounded), Instant.now()) != 1) {
            throw new IllegalStateException("fiscal_integrity_claim_lost");
        }
    }

    private FiscalIntegrityJob authorized(UUID id, String requestedBy, boolean admin) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var job = jobs.findByIdAndCompanyIdAndStoreIdAndInstallationId(
                id, company.getId(), store.getId(), installation.getId())
                .orElseThrow(() -> new IllegalArgumentException("fiscal_integrity_job_not_found"));
        if (!admin && !job.getRequestedBy().equals(requireOwner(requestedBy))) {
            throw new IllegalArgumentException("fiscal_integrity_job_not_found");
        }
        return job;
    }

    private void lockAdmission(UUID companyId, UUID installationId) {
        var key = "fiscal-integrity:" + companyId + ":" + installationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                new MapSqlParameterSource("lockKey", key),
                (org.springframework.jdbc.core.RowCallbackHandler) row -> { });
    }

    private static String requireOwner(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fiscal_integrity_owner_required");
        return value.trim();
    }

    private static FiscalIntegrityJobView view(FiscalIntegrityJob job) {
        return FiscalIntegrityJobView.from(job);
    }

    private static String evidenceJson(List<String> evidence) {
        try { return JSON.writeValueAsString(evidence == null ? List.of() : evidence); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("fiscal_integrity_evidence_serialization", exception); }
    }

}

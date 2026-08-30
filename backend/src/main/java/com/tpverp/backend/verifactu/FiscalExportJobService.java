package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Base64;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Durable, bounded-memory worker for fiscal evidence exports. */
@Service
public class FiscalExportJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FiscalExportJobService.class);
    private static final int BATCH_SIZE = 500;
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final long DEFAULT_MAX_RECORDS = 1_000_000L;
    private static final long DEFAULT_MAX_XML_BYTES = 2_000_000_000L;
    private static final long MAX_ACTIVE_PER_USER_SCOPE = 3L;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final SecureRandom DOWNLOAD_TOKEN_RANDOM = new SecureRandom();
    private static final Duration DOWNLOAD_TOKEN_TTL = Duration.ofMinutes(2);
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final FiscalExportJobRepository jobs;
    private final FiscalRequiredSubmissionRepository submissions;
    private final FiscalExportJobEvidenceService evidence;
    private final NamedParameterJdbcTemplate jdbc;
    private final Path directory;
    private final long maxRecords;
    private final long maxXmlBytes;
    private final VerifactuOfficialXsdValidator xsdValidator;

    public FiscalExportJobService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            FiscalExportJobRepository jobs, NamedParameterJdbcTemplate jdbc,
            FiscalExportJobEvidenceService evidence) {
        this(organization, installations, licenses, jobs, jdbc, evidence,
                null,
                Path.of(System.getProperty("java.io.tmpdir"), "tpv-erp", "fiscal-exports").toString(),
                DEFAULT_MAX_RECORDS, DEFAULT_MAX_XML_BYTES);
    }

    public FiscalExportJobService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            FiscalExportJobRepository jobs, NamedParameterJdbcTemplate jdbc,
            FiscalExportJobEvidenceService evidence,
            FiscalRequiredSubmissionRepository submissions,
            @Value("${tpv.verifactu.export-job-directory:${user.home}/.tpv-erp/fiscal-exports}") String directory,
            @Value("${tpv.verifactu.export-job-max-records:1000000}") long maxRecords,
            @Value("${tpv.verifactu.export-job-max-xml-bytes:2000000000}") long maxXmlBytes) {
        this(organization, installations, licenses, jobs, jdbc, evidence, submissions, directory,
                maxRecords, maxXmlBytes, new VerifactuOfficialXsdValidator());
    }

    @Autowired
    public FiscalExportJobService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            FiscalExportJobRepository jobs, NamedParameterJdbcTemplate jdbc,
            FiscalExportJobEvidenceService evidence,
            FiscalRequiredSubmissionRepository submissions,
            @Value("${tpv.verifactu.export-job-directory:${user.home}/.tpv-erp/fiscal-exports}")
            String directory,
            @Value("${tpv.verifactu.export-job-max-records:1000000}") long maxRecords,
            @Value("${tpv.verifactu.export-job-max-xml-bytes:2000000000}") long maxXmlBytes,
            VerifactuOfficialXsdValidator xsdValidator) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.jobs = jobs;
        this.submissions = submissions;
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        if (maxRecords < 1 || maxXmlBytes < 1) {
            throw new IllegalArgumentException("Los limites de exportacion deben ser positivos");
        }
        this.maxRecords = Math.min(maxRecords, DEFAULT_MAX_RECORDS);
        this.maxXmlBytes = Math.min(maxXmlBytes, DEFAULT_MAX_XML_BYTES);
        this.xsdValidator = Objects.requireNonNull(xsdValidator, "xsdValidator");
    }

    @Transactional
    public FiscalExportJobView create(FiscalExportJobRequest request, String requestedBy) {
        validate(request, requestedBy);
        var store = organization.currentStore();
        var company = organization.currentCompany();
        if (store == null || company == null || store.getEmpresa() == null
                || !company.getId().equals(store.getEmpresa().getId())) {
            throw new IllegalStateException("El contexto fiscal actual no es valido");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var normalized = normalize(request);
        // The active-job cap is a count-then-insert decision.  Serialize that
        // decision per tenant/store/installation/user/scope for this transaction;
        // the unique job id alone cannot prevent two concurrent admissions.
        lockAdmission(company.getId(), store.getId(), installation.getId(),
                requestedBy.trim(), normalized.scope());
        var active = jobs.countByCompanyIdAndStoreIdAndInstallationIdAndRequestedByAndScopeAndStatusIn(
                company.getId(), store.getId(), installation.getId(), requestedBy.trim(), normalized.scope(),
                List.of(FiscalExportJobStatus.QUEUED, FiscalExportJobStatus.RUNNING));
        if (active >= MAX_ACTIVE_PER_USER_SCOPE) {
            throw new IllegalStateException("fiscal_export_active_limit");
        }
        var snapshot = snapshotSequence(request.kind(), company.getId(), store.getId(), installation.getId());
        var executionMode = jdbc.getJdbcTemplate().query(
                "select modo_actual from configuracion_verifactu where empresa_id = ?",
                (result, row) -> FiscalMode.valueOf(result.getString(1)), company.getId())
                .stream().findFirst().orElse(FiscalMode.PRE_SIF);
        var now = Instant.now();
        var job = jobs.save(new FiscalExportJob(company.getId(), store.getId(), installation.getId(),
                requestedBy.trim(), normalized, executionMode, snapshot, now, now.plus(RETENTION)));
        return view(job);
    }

    private void lockAdmission(UUID companyId, UUID storeId, UUID installationId,
            String requestedBy, FiscalExportJobScope scope) {
        var lockKey = companyId + ":" + storeId + ":" + installationId + ":"
                + requestedBy + ":" + scope.name();
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                new MapSqlParameterSource("lockKey", lockKey),
                (org.springframework.jdbc.core.RowCallbackHandler) row -> { });
    }

    public FiscalExportJobView status(UUID id, String requestedBy, boolean admin) {
        var job = authorized(id, requestedBy, admin);
        expireIfNecessary(job);
        return view(job);
    }

    @Transactional
    public FiscalExportJobView retry(UUID id, String requestedBy, boolean admin) {
        var previous = authorized(id, requestedBy, admin);
        if (previous.getStatus() == FiscalExportJobStatus.QUEUED
                || previous.getStatus() == FiscalExportJobStatus.RUNNING) {
            throw new IllegalStateException("fiscal_export_job_retry_not_allowed");
        }
        var request = new FiscalExportJobRequest(previous.getKind(), previous.getPeriodStart(),
                previous.getPeriodEnd(), previous.getRecordIds(), previous.getDateFrom(),
                previous.getDateTo(), previous.getDocumentNumber(), previous.getDocumentNumberPrefix(),
                previous.getOperation(), previous.getDocumentType(), previous.getFiscalMode(),
                previous.getScope());
        var retried = create(request, requestedBy);
        if (previous.getRequiredSubmissionId() != null) {
            var job = jobs.findById(retried.id()).orElseThrow();
            job.attachRequiredSubmission(previous.getRequiredSubmissionId());
            jobs.save(job);
        }
        return retried;
    }

    @Transactional
    public FiscalExportJobView createRequiredSubmissionJob(UUID submissionId,
            OffsetDateTime periodStart, OffsetDateTime periodEnd, String requestedBy) {
        if (submissions == null) throw new IllegalStateException("fiscal_required_submission_unavailable");
        var company = organization.currentCompany();
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var submission = submissions.findForUpdateByIdAndCompanyIdAndInstallationId(
                submissionId, company.getId(), installation.getId())
                .orElseThrow(() -> new IllegalArgumentException("fiscal_required_submission_not_found"));
        if (!"PENDIENTE".equals(submission.getStatus())) {
            throw new IllegalStateException("fiscal_required_submission_not_pending");
        }
        var mode = jdbc.getJdbcTemplate().query(
                "select modo_actual from configuracion_verifactu where empresa_id = ?",
                (result, row) -> FiscalMode.valueOf(result.getString(1)), company.getId())
                .stream().findFirst().orElse(FiscalMode.PRE_SIF);
        if (mode != FiscalMode.NO_VERIFACTU) {
            throw new IllegalStateException("fiscal_required_submission_requires_no_verifactu");
        }
        submission.freezePeriod(periodStart, periodEnd);
        submissions.save(submission);
        periodStart = submission.getPeriodStart();
        periodEnd = submission.getPeriodEnd();
        var request = new FiscalExportJobRequest(FiscalExportKind.BILLING, periodStart, periodEnd,
                List.of(), null, null, null, null, null, null, null, FiscalExportJobScope.PERIOD);
        validate(request, requestedBy);
        var view = create(request, requestedBy);
        var job = jobs.findById(view.id()).orElseThrow();
        job.attachRequiredSubmission(submission.getId());
        jobs.save(job);
        return view(job);
    }

    public Page<FiscalExportJobView> list(int page, int size, String requestedBy, boolean admin) {
        if (page < 0) throw new IllegalArgumentException("fiscal_export_page_invalid");
        var boundedSize = Math.max(1, Math.min(size, 50));
        var store = organization.currentStore();
        var company = organization.currentCompany();
        if (store == null || company == null || store.getEmpresa() == null
                || !company.getId().equals(store.getEmpresa().getId())) {
            throw new IllegalArgumentException("fiscal_export_job_not_found");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var result = admin
                ? jobs.findAllByCompanyIdAndStoreIdAndInstallationIdOrderByCreatedAtDesc(
                        company.getId(), store.getId(), installation.getId(), PageRequest.of(page, boundedSize))
                : jobs.findAllByCompanyIdAndStoreIdAndInstallationIdAndRequestedByOrderByCreatedAtDesc(
                        company.getId(), store.getId(), installation.getId(), requestedBy,
                        PageRequest.of(page, boundedSize));
        return result.map(this::view);
    }

    public Download download(UUID id, String requestedBy, boolean admin) {
        var job = authorized(id, requestedBy, admin);
        expireIfNecessary(job);
        if (job.getStatus() != FiscalExportJobStatus.COMPLETED) {
            throw new IllegalStateException("fiscal_export_job_not_ready");
        }
        var file = safeDownloadPath(job.getFilePath());
        if (file == null) {
            throw new IllegalStateException("fiscal_export_job_file_unavailable");
        }
        try {
            return new Download(file, Files.size(file), "exportacion-fiscal-" + job.getId() + ".zip");
        } catch (IOException exception) {
            throw new IllegalStateException("fiscal_export_job_file_unavailable", exception);
        }
    }

    /**
     * Compatibility download that keeps the authorization and file validation
     * attached to the channel that will actually be streamed. The capability
     * endpoint remains the preferred one; this method exists for older clients
     * still using GET /{id}/download.
     */
    @Transactional
    DownloadHandle openAuthorizedDownload(UUID id, String requestedBy, boolean admin) {
        var job = authorized(id, requestedBy, admin);
        expireIfNecessary(job);
        if (job.getStatus() != FiscalExportJobStatus.COMPLETED) {
            throw new IllegalStateException("fiscal_export_job_not_ready");
        }
        var file = safeDownloadPath(job.getFilePath());
        if (file == null) {
            throw new IllegalStateException("fiscal_export_job_file_unavailable");
        }
        try {
            return openDownloadHandle(file,
                    "exportacion-fiscal-" + job.getId() + ".zip", job.getFileSize());
        } catch (IOException exception) {
            throw new IllegalStateException("fiscal_export_job_file_unavailable", exception);
        }
    }

    /** Issues a short-lived, single-use capability without persisting its plaintext. */
    @Transactional
    public String issueDownloadToken(UUID id, String requestedBy, boolean admin) {
        var job = authorized(id, requestedBy, admin);
        if (job.getStatus() != FiscalExportJobStatus.COMPLETED
                || !job.getExpiresAt().isAfter(Instant.now())
                || safeDownloadPath(job.getFilePath()) == null) {
            throw new IllegalStateException("fiscal_export_job_not_ready");
        }
        var now = Instant.now();
        var expiresAt = now.plus(DOWNLOAD_TOKEN_TTL);
        for (var attempt = 0; attempt < 3; attempt++) {
            var token = randomDownloadToken();
            try {
                jdbc.update("""
                        insert into fiscal_export_download_token
                            (token_hash, job_id, empresa_id, tienda_id, instalacion_id,
                             solicitado_por, expira_en, consumido_en)
                        values (:tokenHash, :jobId, :companyId, :storeId, :installationId,
                                :requestedBy, :expiresAt, null)
                        """, new MapSqlParameterSource()
                        .addValue("tokenHash", sha256(token).toLowerCase(java.util.Locale.ROOT))
                        .addValue("jobId", job.getId())
                        .addValue("companyId", job.getCompanyId())
                        .addValue("storeId", job.getStoreId())
                        .addValue("installationId", job.getInstallationId())
                        // Bind the capability to the authenticated issuer. An
                        // ADMIN may legitimately download another user's job;
                        // persisting the job owner here would lose that audit
                        // binding even though authorization happened above.
                        .addValue("requestedBy", requestedBy)
                        .addValue("expiresAt", expiresAt));
                return token;
            } catch (org.springframework.dao.DuplicateKeyException exception) {
                if (attempt == 2) throw exception;
            }
        }
        throw new IllegalStateException("fiscal_export_download_token_unavailable");
    }

    /**
     * Atomically consumes a capability and returns only a validated filesystem
     * resource. The token row is locked until this transaction commits, so a
     * concurrent request cannot consume the same capability twice.
     */
    @Transactional
    public Download consumeDownloadToken(String token) {
        try (var handle = consumeDownloadTokenForStreaming(token)) {
            return handle.download();
        } catch (IOException exception) {
            throw new IllegalArgumentException("fiscal_export_job_file_unavailable", exception);
        }
    }

    /**
     * Consumes a capability and keeps the validated file handle open for the
     * HTTP response. The caller owns the handle and must close it.
     */
    @Transactional
    DownloadHandle consumeDownloadTokenForStreaming(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("fiscal_export_download_token_invalid");
        }
        var tokenHash = sha256(token).toLowerCase(java.util.Locale.ROOT);
        var rows = jdbc.query("select token_hash, job_id, empresa_id, tienda_id, instalacion_id, "
                + "solicitado_por, expira_en, consumido_en from fiscal_export_download_token "
                + "where token_hash = :tokenHash for update",
                new MapSqlParameterSource("tokenHash", tokenHash),
                (result, rowNum) -> new DownloadTokenRow(
                        result.getString("token_hash"), result.getObject("job_id", UUID.class),
                        result.getObject("empresa_id", UUID.class), result.getObject("tienda_id", UUID.class),
                        result.getObject("instalacion_id", UUID.class), result.getString("solicitado_por"),
                        result.getTimestamp("expira_en").toInstant(),
                        result.getTimestamp("consumido_en") == null
                                ? null : result.getTimestamp("consumido_en").toInstant()));
        var row = rows.stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("fiscal_export_download_token_invalid"));
        var now = Instant.now();
        if (row.consumedAt() != null || !row.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("fiscal_export_download_token_invalid");
        }
        var job = jobs.findByIdAndCompanyIdAndStoreIdAndInstallationId(row.jobId(), row.companyId(),
                row.storeId(), row.installationId()).orElseThrow(
                        () -> new IllegalArgumentException("fiscal_export_download_token_invalid"));
        if (row.requestedBy() == null || row.requestedBy().isBlank()
                || job.getStatus() != FiscalExportJobStatus.COMPLETED
                || !job.getExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("fiscal_export_download_token_invalid");
        }
        var file = safeDownloadPath(job.getFilePath());
        if (file == null) throw new IllegalArgumentException("fiscal_export_job_file_unavailable");
        DownloadHandle handle = null;
        try {
            handle = openDownloadHandle(file, "exportacion-fiscal-" + job.getId() + ".zip",
                    job.getFileSize());
            if (jdbc.update("update fiscal_export_download_token set consumido_en = :consumedAt "
                    + "where token_hash = :tokenHash and consumido_en is null and expira_en > :consumedAt",
                    new MapSqlParameterSource().addValue("consumedAt", now).addValue("tokenHash", tokenHash)) != 1) {
                throw new IllegalArgumentException("fiscal_export_download_token_invalid");
            }
            registerDownloadHandleRollbackCleanup(handle);
            return handle;
        } catch (IOException exception) {
            closeDownloadHandle(handle);
            throw new IllegalArgumentException("fiscal_export_job_file_unavailable", exception);
        } catch (RuntimeException | Error exception) {
            closeDownloadHandle(handle);
            throw exception;
        }
    }

    DownloadHandle openDownloadHandle(Path file, String fileName, long expectedSize) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(file,
                java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        boolean keepOpen = false;
        try {
            var attributes = Files.readAttributes(file,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("fiscal_export_job_file_unavailable");
            }
            var allowed = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            var parent = file.getParent() == null
                    ? null : file.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS);
            var real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (parent == null || !parent.startsWith(allowed) || !real.startsWith(allowed)) {
                throw new IOException("fiscal_export_job_file_unavailable");
            }
            var size = channel.size();
            if (size < 0 || size != expectedSize) {
                throw new IOException("fiscal_export_job_file_unavailable");
            }
            keepOpen = true;
            return new DownloadHandle(new Download(file, size, fileName), channel);
        } finally {
            if (!keepOpen) channel.close();
        }
    }

    private static void closeDownloadHandle(DownloadHandle handle) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (IOException ignored) {
            // Preserve the original download or capability error.
        }
    }

    void registerDownloadHandleRollbackCleanup(DownloadHandle handle) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    closeDownloadHandle(handle);
                }
            }
        });
    }

    private static String randomDownloadToken() {
        var bytes = new byte[32];
        DOWNLOAD_TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Called asynchronously; each query uses a bounded keyset batch and a forward-only callback. */
    public void run(UUID id) {
        var now = Instant.now();
        var executionToken = UUID.randomUUID();
        if (jobs.claimQueued(id, executionToken, now) == 0) return;
        runClaimed(id, executionToken);
    }

    void runClaimed(UUID id) {
        runClaimed(id, null);
    }

    private void runClaimed(UUID id, UUID executionToken) {
        var now = Instant.now();
        // A requeued job is immediately eligible for a new claim.  Therefore a
        // worker must reload by its exact token; accepting a QUEUED row with a
        // null token would let an obsolete worker adopt its old token again.
        var job = executionToken == null
                ? jobs.findById(id).orElse(null)
                : jobs.findByIdAndExecutionToken(id, executionToken).orElse(null);
        if (job == null || (job.getStatus() != FiscalExportJobStatus.RUNNING
                && job.getStatus() != FiscalExportJobStatus.QUEUED)) {
            return;
        }
        job.adoptExecutionToken(executionToken);
        job.markRunning(now);
        Path temporary = null;
        Path finalPath = null;
        XmlBatchWriter archive = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "fiscal-", ".part");
            var digest = MessageDigest.getInstance("SHA-256");
            var progress = new Progress();
            String contentHash;
            try (var stream = Files.newOutputStream(temporary);
                    var zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
                archive = new XmlBatchWriter(zip, digest);
                if (job.getKind() == FiscalExportKind.BILLING) {
                    writeBilling(job, archive, progress);
                } else {
                    writeEvents(job, archive, progress);
                }
                archive.finish();
                contentHash = HexFormat.of().formatHex(digest.digest()).toUpperCase(java.util.Locale.ROOT);
                var exportedAt = Instant.now();
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifest(job, progress, contentHash, exportedAt)
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                progress.exportedAt = exportedAt;
            }
            if (Files.size(temporary) > maxXmlBytes) {
                throw new IllegalStateException("fiscal_export_limit_exceeded_split_period");
            }
            var fileToken = executionToken == null ? "legacy" : executionToken.toString();
            finalPath = directory.resolve(job.getId() + "-" + fileToken + ".zip").normalize();
            moveIntoDirectory(temporary, finalPath);
            temporary = null;
            if (!job.getRecordIds().isEmpty() && progress.processed != job.getRecordIds().size()) {
                throw new IllegalStateException("fiscal_export_selected_records_missing");
            }
            evidence.registerEvidenceAndCompleteJob(job, progress.summary(job), progress.context(job),
                    contentHash, progress.processed, progress.exportedAt, finalPath.toString(),
                    Files.size(finalPath), progress.exportedAt.plus(RETENTION));
        } catch (Exception failure) {
            if (archive != null) archive.abort();
            delete(temporary);
            delete(finalPath);
            LOGGER.error("Fiscal export job {} failed", id, failure);
            job.markFailed("fiscal_export_failed", Instant.now());
            jobs.markFailedIfRunning(id, executionToken, "fiscal_export_failed", Instant.now());
        }
    }

    @Transactional
    public void requeueInterruptedJobs() {
        var now = Instant.now();
        var staleBefore = now.minus(Duration.ofMinutes(2));
        cleanupOrphanParts(now);
        List<FiscalExportJob> running;
        do {
            running = jobs.findTop100ByStatusAndUpdatedAtBeforeOrderByCreatedAtAsc(
                    FiscalExportJobStatus.RUNNING, staleBefore);
            for (var job : running) {
                if (jobs.requeueRunningJob(job.getId(), now, staleBefore) == 1) {
                    delete(safePath(job.getFilePath()));
                    delete(exportPath(job.getId(), job.getExecutionToken()));
                }
            }
        } while (running.size() == 100);
    }

    public List<UUID> queuedIds() {
        return jobs.findTop100ByStatusOrderByCreatedAtAsc(FiscalExportJobStatus.QUEUED)
                .stream().map(FiscalExportJob::getId).toList();
    }

    public void failQueued(UUID id) {
        jobs.failQueued(id, "fiscal_export_queue_unavailable", Instant.now());
    }

    public void expireJobs(Instant now) {
        cleanupOrphanParts(now);
        // Capabilities are operational credentials, not fiscal evidence. Once
        // expired they are removed instead of growing without bound.
        jdbc.update("delete from fiscal_export_download_token where expira_en <= :now",
                new MapSqlParameterSource("now", Timestamp.from(now)));
        var statuses = List.of(FiscalExportJobStatus.QUEUED,
                FiscalExportJobStatus.FAILED, FiscalExportJobStatus.COMPLETED);
        List<FiscalExportJob> expired;
        do {
            expired = jobs.findTop100ByExpiresAtBeforeAndStatusInOrderByExpiresAtAsc(now, statuses);
            for (var job : expired) {
                if (jobs.expireIfEligible(job.getId(), now) == 1) {
                    delete(safePath(job.getFilePath()));
                    job.markExpired(now);
                }
            }
        } while (expired.size() == 100);
    }

    private void writeBilling(FiscalExportJob job, XmlBatchWriter archive,
            Progress progress) {
        if (job.getRequiredSubmissionId() != null) {
            writeRequiredBilling(job, archive, progress);
            return;
        }
        var cursor = 0L;
        boolean hasMore;
        do {
            var parameters = parameters(job, cursor);
            var batch = new BatchProgress();
            jdbc.query(billingSql(job), parameters, result -> {
                if (batch.rows < BATCH_SIZE) {
                    var xml = result.getString("xml");
                    if (xml == null) throw new IllegalStateException("fiscal_export_missing_artifact");
                    ensureWithinLimits(progress, xml);
                    progress.captureBilling(result);
                    archive.write("registro-facturacion", ++progress.processed, xml, progress.processed > 1);
                    batch.rows++;
                    batch.lastSequence = result.getLong("secuencia");
                } else {
                    batch.hasMore = true;
                }
            });
            if (batch.rows == 0) break;
            cursor = batch.lastSequence;
            progress.hasMore = batch.hasMore;
            persistProgress(job, progress);
            hasMore = batch.hasMore;
        } while (hasMore);
    }

    /**
     * Required-submission exports are the official AEAT RemisionRequerimiento
     * envelope, not a collection of unrelated record XML files. The envelope
     * is assembled to a temporary file and copied to the ZIP in a streaming
     * fashion, keeping memory independent of the number of records.
     */
    private void writeRequiredBilling(FiscalExportJob job, XmlBatchWriter archive,
            Progress progress) {
        var holder = new RequirementWriterHolder();
        try {
            Files.createDirectories(directory);
            var cursor = 0L;
            boolean hasMore;
            do {
                var parameters = parameters(job, cursor)
                        .addValue("requiredMode", FiscalMode.NO_VERIFACTU.name());
                var batch = new BatchProgress();
                jdbc.query(requiredBillingSql(job), parameters, result -> {
                    if (batch.rows < BATCH_SIZE) {
                        var xml = result.getString("xml");
                        var issuerName = result.getString("artifact_issuer_name");
                        var issuerTaxId = result.getString("artifact_issuer_tax_id");
                        var xmlHash = result.getString("artifact_xml_hash");
                        validateRequiredRow(result, xml, xmlHash, issuerName, issuerTaxId, holder);
                        if (holder.writer == null || holder.writer.records() >= 1_000) {
                            if (holder.writer != null) {
                                holder.writer.close();
                                try {
                                    validateAndAccountRequirementEnvelope(holder.path, progress);
                                    archive.writeTopLevelFile(requirementFileName(++holder.envelopeNumber),
                                            holder.path, false);
                                } catch (IOException exception) {
                                    throw new UncheckedIOException(exception);
                                }
                                delete(holder.path);
                            }
                            try {
                                holder.path = Files.createTempFile(directory,
                                        "fiscal-requirement-", ".xml");
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                            holder.writer = new FiscalRequirementXmlStreamWriter(
                                    holder.path, issuerName, issuerTaxId,
                                    new FiscalRequirementContext(requiredReference(job), false));
                        }
                        ensureRecordLimit(progress);
                        holder.writer.appendSignedRecord(xml);
                        progress.captureBilling(result);
                        progress.processed++;
                        batch.rows++;
                        batch.lastSequence = result.getLong("secuencia");
                    } else {
                        batch.hasMore = true;
                    }
                });
                if (batch.rows == 0) break;
                cursor = batch.lastSequence;
                progress.hasMore = batch.hasMore;
                persistProgress(job, progress);
                hasMore = batch.hasMore;
            } while (hasMore);
            if (holder.writer == null || progress.processed == 0) {
                throw new IllegalStateException("fiscal_required_submission_no_signed_records");
            }
            holder.writer.markFinished();
            holder.writer.close();
            validateAndAccountRequirementEnvelope(holder.path, progress);
            archive.writeTopLevelFile(requirementFileName(++holder.envelopeNumber), holder.path, false);
            progress.envelopeCount = holder.envelopeNumber;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (holder.writer != null) holder.writer.close();
            delete(holder.path);
        }
    }

    private static String requirementFileName(int envelopeNumber) {
        return "requerimiento-aeat-" + String.format(java.util.Locale.ROOT, "%06d", envelopeNumber)
                + ".xml";
    }

    private void validateAndAccountRequirementEnvelope(Path path, Progress progress) {
        xsdValidator.validate(path);
        try {
            var bytes = Files.size(path);
            if (bytes > maxXmlBytes - progress.xmlBytes) {
                throw new IllegalStateException("fiscal_export_limit_exceeded_split_period");
            }
            progress.xmlBytes += bytes;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void ensureRecordLimit(Progress progress) {
        if (progress.processed >= maxRecords) {
            throw new IllegalStateException("fiscal_export_limit_exceeded_split_period");
        }
    }

    private String requiredReference(FiscalExportJob job) {
        if (submissions == null || job.getRequiredSubmissionId() == null) {
            throw new IllegalStateException("fiscal_required_submission_unavailable");
        }
        return submissions.findByIdAndCompanyIdAndInstallationId(job.getRequiredSubmissionId(),
                        job.getCompanyId(), job.getInstallationId())
                .map(FiscalRequiredSubmission::getReference)
                .filter(reference -> !reference.isBlank())
                .orElseThrow(() -> new IllegalStateException("fiscal_required_submission_not_found"));
    }

    private void validateRequiredRow(java.sql.ResultSet result, String xml, String xmlHash,
            String issuerName, String issuerTaxId, RequirementWriterHolder holder)
            throws java.sql.SQLException {
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("fiscal_required_submission_unsigned_record");
        }
        verifyRequiredArtifactHash(xml, xmlHash);
        if (issuerName == null || issuerName.isBlank() || issuerTaxId == null || issuerTaxId.isBlank()) {
            throw new IllegalStateException("fiscal_required_submission_identity_missing");
        }
        var recordTaxId = result.getString("nif_emisor");
        if (!issuerTaxId.equals(recordTaxId)) {
            throw new IllegalStateException("fiscal_required_submission_identity_mismatch");
        }
        if (holder.issuerTaxId != null && !holder.issuerTaxId.equals(issuerTaxId)) {
            throw new IllegalStateException("fiscal_required_submission_mixed_issuer");
        }
        if (holder.issuerName != null && !holder.issuerName.equals(issuerName)) {
            throw new IllegalStateException("fiscal_required_submission_mixed_issuer_name");
        }
        holder.issuerTaxId = issuerTaxId;
        holder.issuerName = issuerName;
    }

    private String requiredBillingSql(FiscalExportJob job) {
        var sql = new StringBuilder("""
                select record.id, record.secuencia, record.operacion, record.nif_emisor,
                       record.serie_numero, record.fecha_expedicion, record.generado_en,
                       record.zona_horaria, record.cuota_total, record.importe_total, record.huella,
                       artifact.xml_firmado as xml,
                       artifact.xml_hash as artifact_xml_hash,
                       artifact.obligado_nombre as artifact_issuer_name,
                       artifact.obligado_nif as artifact_issuer_tax_id
                from registro_fiscal record
                left join artefacto_registro_fiscal artifact on artifact.registro_id = record.id
                where record.empresa_id = :companyId
                  and record.tienda_id = :storeId
                  and record.instalacion_id = :installationId
                  and record.modo_fiscal = :requiredMode
                  and record.secuencia > :cursor
                  and record.secuencia <= :snapshotSequence
                """);
        if (job.getPeriodStart() != null) sql.append(" and record.generado_en >= :periodStart\n");
        if (job.getPeriodEnd() != null) sql.append(" and record.generado_en <= :periodEnd\n");
        return sql.append(" order by record.secuencia asc, record.id asc limit :limit\n").toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    static void verifyRequiredArtifactHash(String xml, String expectedHash) {
        if (expectedHash == null || !expectedHash.equalsIgnoreCase(sha256(xml))) {
            throw new IllegalStateException("fiscal_required_submission_artifact_hash_mismatch");
        }
    }

    private void writeEvents(FiscalExportJob job, XmlBatchWriter archive,
            Progress progress) {
        var cursor = 0L;
        boolean hasMore;
        do {
            var parameters = new MapSqlParameterSource()
                    .addValue("companyId", job.getCompanyId())
                    .addValue("installationId", job.getInstallationId())
                    .addValue("snapshotSequence", job.getSnapshotSequence())
                    .addValue("cursor", cursor)
                    .addValue("limit", BATCH_SIZE + 1);
            if (job.getPeriodStart() != null) parameters.addValue("periodStart", job.getPeriodStart());
            if (job.getPeriodEnd() != null) parameters.addValue("periodEnd", job.getPeriodEnd());
            var batch = new BatchProgress();
            var sql = new StringBuilder("""
                    select id, secuencia, tipo_evento, generado_en, huella_evento, xml_firmado
                    from registro_evento_fiscal
                    where empresa_id = :companyId
                      and instalacion_id = :installationId
                      and secuencia > :cursor
                      and secuencia <= :snapshotSequence
                    """);
            if (job.getPeriodStart() != null) sql.append(" and generado_en >= :periodStart\n");
            if (job.getPeriodEnd() != null) sql.append(" and generado_en <= :periodEnd\n");
            sql.append(" order by secuencia asc, id asc limit :limit\n");
            jdbc.query(sql.toString(), parameters, result -> {
                if (batch.rows < BATCH_SIZE) {
                    var xml = result.getString("xml_firmado");
                    if (xml == null) throw new IllegalStateException("fiscal_export_missing_event_xml");
                    ensureWithinLimits(progress, xml);
                    progress.captureEvent(result);
                    archive.write("evento", ++progress.processed, xml, progress.processed > 1);
                    batch.rows++;
                    batch.lastSequence = result.getLong("secuencia");
                } else {
                    batch.hasMore = true;
                }
            });
            if (batch.rows == 0) break;
            cursor = batch.lastSequence;
            progress.hasMore = batch.hasMore;
            persistProgress(job, progress);
            hasMore = batch.hasMore;
        } while (hasMore);
    }

    private String billingSql(FiscalExportJob job) {
        var sql = new StringBuilder("""
                select record.id, record.secuencia, record.operacion, record.nif_emisor,
                       record.serie_numero, record.fecha_expedicion, record.generado_en,
                       record.zona_horaria,
                       record.cuota_total, record.importe_total, record.huella,
                       coalesce(artifact.xml_firmado, artifact.xml_sin_firmar) as xml
                from registro_fiscal record
                left join artefacto_registro_fiscal artifact on artifact.registro_id = record.id
                where record.empresa_id = :companyId
                  and record.tienda_id = :storeId
                  and record.instalacion_id = :installationId
                  and record.secuencia > :cursor
                  and record.secuencia <= :snapshotSequence
                """);
        if (job.getPeriodStart() != null) sql.append(" and record.generado_en >= :periodStart\n");
        if (job.getPeriodEnd() != null) sql.append(" and record.generado_en <= :periodEnd\n");
        if (job.getDateFrom() != null) sql.append(" and record.fecha_expedicion >= :dateFrom\n");
        if (job.getDateTo() != null) sql.append(" and record.fecha_expedicion <= :dateTo\n");
        if (job.getDocumentNumber() != null) sql.append(" and lower(record.serie_numero) = :documentNumber\n");
        if (job.getDocumentNumberPrefix() != null) {
            sql.append(" and lower(record.serie_numero) like :documentNumberPrefix || '%' escape '\\'\n");
        }
        if (!job.getRecordIds().isEmpty()) sql.append(" and record.id in (:recordIds)\n");
        if (job.getOperation() != null) sql.append(" and record.operacion = :operation\n");
        if (job.getDocumentType() != null) sql.append(" and record.tipo_documento_fiscal = :documentType\n");
        if (job.getFiscalMode() != null) sql.append(" and record.modo_fiscal = :fiscalMode\n");
        return sql.append(" order by record.secuencia asc, record.id asc limit :limit\n").toString();
    }

    private MapSqlParameterSource parameters(FiscalExportJob job, long cursor) {
        var parameters = new MapSqlParameterSource()
                .addValue("companyId", job.getCompanyId())
                .addValue("storeId", job.getStoreId())
                .addValue("installationId", job.getInstallationId())
                .addValue("snapshotSequence", job.getSnapshotSequence())
                .addValue("cursor", cursor)
                .addValue("limit", BATCH_SIZE + 1);
        if (job.getPeriodStart() != null) parameters.addValue("periodStart", job.getPeriodStart());
        if (job.getPeriodEnd() != null) parameters.addValue("periodEnd", job.getPeriodEnd());
        if (job.getDateFrom() != null) parameters.addValue("dateFrom", job.getDateFrom());
        if (job.getDateTo() != null) parameters.addValue("dateTo", job.getDateTo());
        if (job.getDocumentNumber() != null) parameters.addValue("documentNumber",
                job.getDocumentNumber().toLowerCase(java.util.Locale.ROOT));
        if (job.getDocumentNumberPrefix() != null) parameters.addValue("documentNumberPrefix",
                escapeLike(job.getDocumentNumberPrefix()).toLowerCase(java.util.Locale.ROOT));
        if (!job.getRecordIds().isEmpty()) parameters.addValue("recordIds", job.getRecordIds());
        if (job.getOperation() != null) parameters.addValue("operation", job.getOperation().name());
        if (job.getDocumentType() != null) parameters.addValue("documentType", job.getDocumentType().name());
        if (job.getFiscalMode() != null) parameters.addValue("fiscalMode", job.getFiscalMode().name());
        return parameters;
    }

    void persistProgress(FiscalExportJob job, Progress progress) {
        persistProgress(job, progress, job.getExecutionToken());
    }

    private void persistProgress(FiscalExportJob job, Progress progress, UUID executionToken) {
        job.markProgress(progress.processed, progress.hasMore, Instant.now());
        if (jobs.updateProgress(job.getId(), executionToken, progress.processed,
                progress.hasMore, job.getUpdatedAt()) != 1) {
            throw new IllegalStateException("fiscal_export_claim_lost");
        }
    }

    private FiscalExportJob authorized(UUID id, String requestedBy, boolean admin) {
        if (id == null || requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("fiscal_export_job_not_found");
        }
        var store = organization.currentStore();
        var company = organization.currentCompany();
        if (store == null || company == null || store.getEmpresa() == null
                || !company.getId().equals(store.getEmpresa().getId())) {
            throw new IllegalArgumentException("fiscal_export_job_not_found");
        }
        var installation = FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
        var job = jobs.findByIdAndCompanyIdAndStoreIdAndInstallationId(id, company.getId(),
                store.getId(), installation.getId()).orElseThrow(
                        () -> new IllegalArgumentException("fiscal_export_job_not_found"));
        if (!admin && !job.getRequestedBy().equals(requestedBy.trim())) {
            throw new IllegalArgumentException("fiscal_export_job_not_found");
        }
        return job;
    }

    @Transactional
    void expireIfNecessary(FiscalExportJob job) {
        if (job.getStatus() != FiscalExportJobStatus.EXPIRED
                && job.getStatus() != FiscalExportJobStatus.RUNNING
                && job.getExpiresAt().isBefore(Instant.now())
                && jobs.expireIfEligible(job.getId(), Instant.now()) == 1) {
            delete(safePath(job.getFilePath()));
            job.markExpired(Instant.now());
        }
    }

    private FiscalExportJobView view(FiscalExportJob job) {
        var available = job.getStatus() == FiscalExportJobStatus.COMPLETED
                && safeDownloadPath(job.getFilePath()) != null;
        return FiscalExportJobView.from(job, available, job.getScope());
    }

    private Path safeDownloadPath(String value) {
        var file = safePath(value);
        if (file == null || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            var allowed = directory.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            var parent = file.getParent() == null
                    ? null : file.getParent().toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            var real = file.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return parent != null && parent.startsWith(allowed) && real.startsWith(allowed) ? file : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private long snapshotSequence(FiscalExportKind kind, UUID companyId, UUID storeId, UUID installationId) {
        var table = kind == FiscalExportKind.EVENTS ? "registro_evento_fiscal" : "registro_fiscal";
        var storeClause = kind == FiscalExportKind.EVENTS ? "" : " and tienda_id = ?";
        var value = jdbc.getJdbcTemplate().queryForObject(
                "select coalesce(max(secuencia), 0) from " + table
                        + " where empresa_id = ?" + storeClause + " and instalacion_id = ?", Long.class,
                kind == FiscalExportKind.EVENTS
                        ? new Object[] { companyId, installationId }
                        : new Object[] { companyId, storeId, installationId });
        return value == null ? 0L : value;
    }

    private static FiscalExportJobRequest normalize(FiscalExportJobRequest request) {
        return new FiscalExportJobRequest(request.kind(), request.periodStart(), request.periodEnd(),
                List.copyOf(request.safeRecordIds()),
                request.dateFrom(), request.dateTo(), bounded(request.documentNumber()),
                bounded(request.documentNumberPrefix()), request.operation(), request.documentType(),
                request.fiscalMode(), request.scope());
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim();
        if (normalized.length() > 64) throw new IllegalArgumentException("El filtro de documento supera 64 caracteres");
        return normalized;
    }

    private static void validate(FiscalExportJobRequest request, String requestedBy) {
        if (request == null || request.kind() == null) throw new IllegalArgumentException("El tipo de exportacion es obligatorio");
        if (requestedBy == null || requestedBy.isBlank()) throw new IllegalArgumentException("El usuario solicitante es obligatorio");
        if ((request.documentNumber() != null && request.documentNumber().isBlank())
                || (request.documentNumberPrefix() != null && request.documentNumberPrefix().isBlank())) {
            throw new IllegalArgumentException("fiscal_export_document_filter_empty");
        }
        if (request.safeRecordIds().size() > 1000
                || request.safeRecordIds().stream().anyMatch(Objects::isNull)
                || request.safeRecordIds().stream().distinct().count() != request.safeRecordIds().size()) {
            throw new IllegalArgumentException("La seleccion de registros no es valida");
        }
        if (request.kind() == FiscalExportKind.EVENTS
                && (!request.safeRecordIds().isEmpty() || request.dateFrom() != null || request.dateTo() != null
                || request.documentNumber() != null || request.documentNumberPrefix() != null
                || request.operation() != null || request.documentType() != null
                || request.fiscalMode() != null)) {
            throw new IllegalArgumentException("Los filtros de registros solo admiten exportaciones BILLING");
        }
        if (!request.hasValidPeriod() || !request.hasValidIssueDates() || !request.hasSingleNumberFilter()) {
            throw new IllegalArgumentException("Los filtros de exportacion no son validos");
        }
        if (!request.hasValidScope()) {
            throw new IllegalArgumentException("fiscal_export_scope_invalid");
        }
    }

    private String manifest(FiscalExportJob job, Progress progress, String hash,
            Instant exportedAt) {
        var scope = job.getScope();
        return "{\n"
                + "  \"exportId\": " + jsonString(job.getId()) + ",\n"
                + "  \"kind\": " + jsonString(job.getKind()) + ",\n"
                + "  \"exportedAt\": " + jsonString(exportedAt) + ",\n"
                + "  \"scope\": " + jsonString(scope) + ",\n"
                + "  \"executionMode\": " + jsonString(job.getExecutionMode()) + ",\n"
                + "  \"snapshotSequence\": " + job.getSnapshotSequence() + ",\n"
                + "  \"limits\": {\"maxRecords\": " + maxRecords
                + ", \"maxXmlBytes\": " + maxXmlBytes + "},\n"
                + "  \"archiveFormat\": \"ZIP_BATCHES\",\n"
                + "  \"batchSize\": " + BATCH_SIZE + ",\n"
                + "  \"periodStart\": " + jsonString(job.getPeriodStart()) + ",\n"
                + "  \"periodEnd\": " + jsonString(job.getPeriodEnd()) + ",\n"
                + "  \"companyId\": " + jsonString(job.getCompanyId()) + ",\n"
                + "  \"storeId\": " + jsonString(job.getStoreId()) + ",\n"
                + "  \"installationId\": " + jsonString(job.getInstallationId()) + ",\n"
                + "  \"filters\": {\"periodStart\": " + jsonString(job.getPeriodStart())
                + ", \"periodEnd\": " + jsonString(job.getPeriodEnd())
                + ", \"dateFrom\": " + jsonString(job.getDateFrom())
                + ", \"dateTo\": " + jsonString(job.getDateTo())
                + ", \"documentNumber\": " + jsonString(job.getDocumentNumber())
                + ", \"documentNumberPrefix\": " + jsonString(job.getDocumentNumberPrefix())
                + ", \"operation\": " + jsonString(job.getOperation())
                + ", \"documentType\": " + jsonString(job.getDocumentType())
                + ", \"fiscalMode\": " + jsonString(job.getFiscalMode())
                + ", \"recordIds\": " + jsonArray(job.getRecordIds()) + "},\n"
                + "  \"recordCount\": " + progress.processed + ",\n"
                + "  \"firstRecord\": " + progress.firstRecordJson(job.getKind()) + ",\n"
                + "  \"lastRecord\": " + progress.lastRecordJson(job.getKind()) + ",\n"
                + "  \"contentHash\": " + jsonString(hash) + ",\n"
                + "  \"files\": " + (progress.envelopeCount > 0
                        ? progress.envelopeCount : progress.processed) + ",\n"
                + "  \"envelopeCount\": " + progress.envelopeCount + "\n"
                + "}";
    }

    private static String jsonArray(List<UUID> values) {
        return values.stream().map(FiscalExportJobService::jsonString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String jsonString(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("fiscal_export_manifest_invalid", exception);
        }
    }

    private void ensureWithinLimits(Progress progress, String xml) {
        if (progress.processed >= maxRecords) {
            throw new IllegalStateException("fiscal_export_limit_exceeded_split_period");
        }
        var bytes = xml.getBytes(StandardCharsets.UTF_8).length;
        var separator = progress.processed == 0 ? 0 : 1;
        if (bytes > maxXmlBytes - progress.xmlBytes - separator) {
            throw new IllegalStateException("fiscal_export_limit_exceeded_split_period");
        }
        progress.xmlBytes += bytes + separator;
    }

    private void moveIntoDirectory(Path source, Path target) throws IOException {
        if (!target.startsWith(directory)) throw new IOException("Ruta de exportacion no valida");
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path safePath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var path = Path.of(value).toAbsolutePath().normalize();
            return path.startsWith(directory) ? path : null;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private Path exportPath(UUID jobId, UUID executionToken) {
        if (jobId == null) return null;
        var fileToken = executionToken == null ? "legacy" : executionToken.toString();
        return directory.resolve(jobId + "-" + fileToken + ".zip").normalize();
    }

    private static void delete(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private void cleanupOrphanParts(Instant now) {
        if (!Files.isDirectory(directory)) return;
        var cutoff = now.minus(Duration.ofHours(1));
        try (var paths = Files.newDirectoryStream(directory, "fiscal-*.part")) {
            for (var path : paths) {
                if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.getLastModifiedTime(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isBefore(cutoff)) delete(path);
            }
        } catch (IOException ignored) {
            LOGGER.warn("No se pudieron limpiar temporales de exportacion fiscal en {}", directory);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record Download(Path path, long size, String fileName) {}

    /** One-shot resource backed by the channel validated at capability consumption. */
    static final class DownloadHandle implements AutoCloseable {
        private final Download download;
        private final SeekableByteChannel channel;
        private boolean streamOpened;

        private DownloadHandle(Download download, SeekableByteChannel channel) {
            this.download = download;
            this.channel = channel;
        }

        Download download() {
            return download;
        }

        InputStream openStream() throws IOException {
            synchronized (this) {
                if (streamOpened) {
                    throw new IOException("fiscal_export_download_stream_already_open");
                }
                streamOpened = true;
            }
            try {
                return Channels.newInputStream(channel);
            } catch (RuntimeException | Error exception) {
                closeDownloadHandle(this);
                throw exception;
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }

        boolean isOpen() {
            return channel.isOpen();
        }
    }

    private record DownloadTokenRow(String tokenHash, UUID jobId, UUID companyId, UUID storeId,
            UUID installationId, String requestedBy, Instant expiresAt, Instant consumedAt) {}

    /** Stores at most one batch of XML names in a nested ZIP central directory. */
    private final class XmlBatchWriter {
        private final ZipOutputStream outer;
        private final MessageDigest digest;
        private Path innerPath;
        private ZipOutputStream inner;
        private int entries;
        private long batchNumber;

        private XmlBatchWriter(ZipOutputStream outer, MessageDigest digest) {
            this.outer = outer;
            this.digest = digest;
        }

        private void write(String prefix, long number, String xml, boolean separator) {
            try {
                var bytes = xml.getBytes(StandardCharsets.UTF_8);
                if (separator) digest.update((byte) '\n');
                digest.update(bytes);
                if (inner == null) {
                    innerPath = Files.createTempFile(directory, "fiscal-batch-", ".part");
                    inner = new ZipOutputStream(Files.newOutputStream(innerPath), StandardCharsets.UTF_8);
                    entries = 0;
                }
                inner.putNextEntry(new ZipEntry(prefix + "-" + String.format("%09d", number) + ".xml"));
                inner.write(bytes);
                inner.closeEntry();
                if (++entries == BATCH_SIZE) flushBatch();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private void finish() throws IOException {
            if (inner != null) flushBatch();
        }

        private void writeTopLevelFile(String name, Path source, boolean separator)
                throws IOException {
            if (inner != null) flushBatch();
            if (separator) digest.update((byte) '\n');
            outer.putNextEntry(new ZipEntry(name));
            try (var input = Files.newInputStream(source)) {
                var buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    digest.update(buffer, 0, read);
                    outer.write(buffer, 0, read);
                }
            }
            outer.closeEntry();
        }

        private void flushBatch() throws IOException {
            inner.close();
            outer.putNextEntry(new ZipEntry("lotes/lote-" + String.format("%09d", ++batchNumber) + ".zip"));
            Files.copy(innerPath, outer);
            outer.closeEntry();
            delete(innerPath);
            inner = null;
            innerPath = null;
        }

        private void abort() {
            try { if (inner != null) inner.close(); } catch (IOException ignored) { }
            delete(innerPath);
        }
    }

    private static final class BatchProgress {
        private int rows;
        private long lastSequence;
        private boolean hasMore;
    }

    private static final class RequirementWriterHolder {
        private FiscalRequirementXmlStreamWriter writer;
        private Path path;
        private int envelopeNumber;
        private String issuerName;
        private String issuerTaxId;
    }
    private static final class Progress {
        private long processed;
        private boolean hasMore;
        private UUID firstId;
        private long firstSequence;
        private UUID lastId;
        private long lastSequence;
        private String firstIssuerTaxId;
        private String firstNumber;
        private java.time.LocalDate firstIssueDate;
        private String firstHash;
        private OffsetDateTime firstGeneratedAt;
        private String lastIssuerTaxId;
        private String lastNumber;
        private java.time.LocalDate lastIssueDate;
        private String lastHash;
        private OffsetDateTime lastGeneratedAt;
        private long altaCount;
        private long cancellationCount;
        private BigDecimal tax = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private String firstEventType;
        private OffsetDateTime firstEventGeneratedAt;
        private String firstEventHash;
        private String lastEventType;
        private OffsetDateTime lastEventGeneratedAt;
        private String lastEventHash;
        private Instant exportedAt;
        private long xmlBytes;
        private int envelopeCount;

        private void captureBilling(java.sql.ResultSet result) throws java.sql.SQLException {
            var issuer = result.getString("nif_emisor");
            var id = result.getObject("id", UUID.class);
            var sequence = result.getLong("secuencia");
            var number = result.getString("serie_numero");
            var issueDate = result.getObject("fecha_expedicion", java.time.LocalDate.class);
            var generated = result.getTimestamp("generado_en").toInstant()
                    .atZone(ZoneId.of(result.getString("zona_horaria"))).toOffsetDateTime();
            var hash = result.getString("huella");
            if (processed == 0) {
                firstId = id; firstSequence = sequence;
                firstIssuerTaxId = issuer; firstNumber = number; firstIssueDate = issueDate;
                firstGeneratedAt = generated; firstHash = hash;
            }
            lastIssuerTaxId = issuer; lastNumber = number; lastIssueDate = issueDate;
            lastId = id; lastSequence = sequence;
            lastGeneratedAt = generated; lastHash = hash;
            var operation = result.getString("operacion");
            if ("ALTA".equals(operation)) {
                altaCount++;
                var rowTax = result.getBigDecimal("cuota_total");
                var rowAmount = result.getBigDecimal("importe_total");
                if (rowTax != null) tax = tax.add(rowTax);
                if (rowAmount != null) amount = amount.add(rowAmount);
            } else if ("ANULACION".equals(operation)) {
                cancellationCount++;
            }
        }

        private void captureEvent(java.sql.ResultSet result) throws java.sql.SQLException {
            var id = result.getObject("id", UUID.class);
            var sequence = result.getLong("secuencia");
            var type = FiscalEventType.valueOf(result.getString("tipo_evento")).code();
            var generated = FiscalFrozenTimestampReader.read(result.getString("xml_firmado"));
            var persistedDbTimestamp = result.getTimestamp("generado_en").toInstant();
            if (!generated.toInstant().equals(persistedDbTimestamp.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
                throw new IllegalStateException("fiscal_export_event_timestamp_mismatch");
            }
            var hash = result.getString("huella_evento");
            if (processed == 0) {
                firstId = id; firstSequence = sequence;
                firstEventType = type; firstEventGeneratedAt = generated; firstEventHash = hash;
            }
            lastEventType = type; lastEventGeneratedAt = generated; lastEventHash = hash;
            lastId = id; lastSequence = sequence;
        }

        private FiscalEventSummary summary(FiscalExportJob job) {
            return new FiscalEventSummary(job.getKind() == FiscalExportKind.EVENTS ? processed : 0,
                    altaCount, tax, amount, cancellationCount);
        }

        private FiscalExportContext context(FiscalExportJob job) {
            if (processed == 0) {
                return job.getPeriodStart() == null ? FiscalExportContext.empty()
                        : new FiscalExportContext(job.getPeriodStart(), job.getPeriodEnd(), null, null, null, null);
            }
            if (job.getKind() == FiscalExportKind.EVENTS) {
                return new FiscalExportContext(
                        job.getPeriodStart() == null ? firstEventGeneratedAt : job.getPeriodStart(),
                        job.getPeriodEnd() == null ? lastEventGeneratedAt : job.getPeriodEnd(), null, null,
                        new FiscalExportContext.EventBoundary(firstEventType, firstEventGeneratedAt, firstEventHash),
                        new FiscalExportContext.EventBoundary(lastEventType, lastEventGeneratedAt, lastEventHash));
            }
            return new FiscalExportContext(
                    job.getPeriodStart() == null ? firstGeneratedAt : job.getPeriodStart(),
                    job.getPeriodEnd() == null ? lastGeneratedAt : job.getPeriodEnd(),
                    new FiscalExportContext.BillingBoundary(firstIssuerTaxId, firstNumber, firstIssueDate, firstHash),
                    new FiscalExportContext.BillingBoundary(lastIssuerTaxId, lastNumber, lastIssueDate, lastHash),
                    null, null);
        }

        private String firstRecordJson(FiscalExportKind kind) {
            if (firstId == null) return "null";
            var number = kind == FiscalExportKind.BILLING ? firstNumber : firstEventType;
            var generated = kind == FiscalExportKind.BILLING ? firstGeneratedAt : firstEventGeneratedAt;
            var hash = kind == FiscalExportKind.BILLING ? firstHash : firstEventHash;
            return "{\"recordId\":" + jsonString(firstId) + ",\"sequence\":" + firstSequence
                    + ",\"number\":" + jsonString(number) + ",\"generatedAt\":"
                    + jsonString(generated) + ",\"hash\":" + jsonString(hash) + "}";
        }

        private String lastRecordJson(FiscalExportKind kind) {
            if (lastId == null) return "null";
            var number = kind == FiscalExportKind.BILLING ? lastNumber : lastEventType;
            var generated = kind == FiscalExportKind.BILLING ? lastGeneratedAt : lastEventGeneratedAt;
            var hash = kind == FiscalExportKind.BILLING ? lastHash : lastEventHash;
            return "{\"recordId\":" + jsonString(lastId) + ",\"sequence\":" + lastSequence
                    + ",\"number\":" + jsonString(number) + ",\"generatedAt\":"
                    + jsonString(generated) + ",\"hash\":" + jsonString(hash) + "}";
        }
    }
}

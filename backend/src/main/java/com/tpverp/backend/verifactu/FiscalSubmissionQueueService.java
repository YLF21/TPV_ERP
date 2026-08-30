package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalSubmissionQueueService {

    private static final int MAX_LEGACY_QUEUE_SIZE = 200;

    private static final List<FiscalSubmissionStatus> VISIBLE_IN_QUEUE = List.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIANDO,
            FiscalSubmissionStatus.ENVIADO,
            FiscalSubmissionStatus.RECHAZADO);
    private static final List<FiscalSubmissionStatus> AUTOMATICALLY_RETRYABLE = List.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIANDO,
            FiscalSubmissionStatus.ENVIADO);
    private static final java.time.Duration RETRY_DELAY = java.time.Duration.ofHours(1);
    private static final java.time.Duration CLAIM_LEASE = java.time.Duration.ofMinutes(2);

    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordRepository records;
    private final CurrentOrganization organization;
    private final Clock clock;
    private final VerifactuDefectClassifier defects;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final FiscalSubmissionScopeFlowRepository flows;
    private FiscalRecordArtifactRepository artifacts;
    private final boolean legacyFallback;

    public FiscalSubmissionQueueService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock,
            VerifactuDefectClassifier defects) {
        this(states, records, organization, clock, defects, null, null, null, true);
    }

    public FiscalSubmissionQueueService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock,
            VerifactuDefectClassifier defects,
            InstallationRepository installations,
            LicenseRepository licenses) {
        // Kept for older focused fixtures/callers that do not provide the
        // scope-flow coordinator. Spring production wiring uses the overload
        // below and therefore gets the bounded projection path.
        this(states, records, organization, clock, defects, installations, licenses, null, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FiscalSubmissionQueueService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock,
            VerifactuDefectClassifier defects,
            InstallationRepository installations,
            LicenseRepository licenses,
            FiscalSubmissionScopeFlowRepository flows) {
        this(states, records, organization, clock, defects, installations, licenses, flows, false);
    }

    private FiscalSubmissionQueueService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock,
            VerifactuDefectClassifier defects,
            InstallationRepository installations,
            LicenseRepository licenses,
            FiscalSubmissionScopeFlowRepository flows,
            boolean legacyFallback) {
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.clock = clock;
        this.defects = defects;
        this.installations = installations;
        this.licenses = licenses;
        this.flows = flows;
        this.legacyFallback = legacyFallback;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setArtifacts(FiscalRecordArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    public boolean batchCoordinatorAvailable() {
        return flows != null && artifacts != null;
    }

    /** Discovers the first due scope and claims up to the official limit. */
    @Transactional
    public Optional<ClaimedFiscalBatch> claimNextBatch(int maximum) {
        if (maximum < 1 || maximum > 1000) {
            throw new IllegalArgumentException("El lote AEAT debe tener entre 1 y 1000 registros");
        }
        if (flows == null || artifacts == null) return Optional.empty();
        var now = Instant.now(clock);
        var candidates = states.findClaimableDiscovery(now, 1000);
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        var candidateIds = candidates.stream().map(FiscalSubmissionState::getRecordId).toList();
        var recordById = records.findAllById(candidateIds).stream()
                .collect(java.util.stream.Collectors.toMap(FiscalRecord::getId, value -> value));
        var artifactById = artifacts.findAllByRecordIdIn(candidateIds).stream()
                .collect(java.util.stream.Collectors.toMap(FiscalRecordArtifact::getRecordId, value -> value));
        var visited = new java.util.HashSet<String>();
        for (var candidate : candidates) {
            var record = java.util.Optional.ofNullable(recordById.get(candidate.getRecordId()))
                    .orElseThrow(() -> new IllegalStateException("registro fiscal no encontrado"));
            var artifact = java.util.Optional.ofNullable(artifactById.get(record.getId()))
                    .orElseThrow(() -> new IllegalStateException("artefacto fiscal no encontrado"));
            var key = record.getCompanyId() + ":" + record.getInstallationId()
                    + ":" + artifact.getEnvironment();
            if (visited.add(key)) {
                var claimed = claimBatch(record.getCompanyId(), record.getInstallationId(),
                        artifact.getEnvironment(), maximum);
                if (claimed.isPresent()) return claimed;
            }
        }
        return Optional.empty();
    }

    /** Manual AEAT TEST uses the same scoped batch coordinator when the target is next. */
    @Transactional
    public Optional<ClaimedFiscalBatch> claimBatchForRecord(
            UUID companyId, UUID installationId, UUID recordId, int maximum) {
        if (recordId == null || flows == null || artifacts == null) return Optional.empty();
        var artifact = artifacts.findByRecordId(recordId).orElseThrow(
                () -> new IllegalStateException("artefacto fiscal no encontrado"));
        return claimBatchLocked(companyId, installationId, artifact.getEnvironment(), maximum, recordId);
    }

    /** Resolves an event record to its immutable scope before opening a batch. */
    @Transactional
    public Optional<ClaimedFiscalBatch> claimBatchForRecord(UUID recordId, int maximum) {
        if (recordId == null || flows == null || artifacts == null) return Optional.empty();
        var record = records.findById(recordId).orElseThrow(
                () -> new IllegalStateException("registro fiscal no encontrado"));
        var artifact = artifacts.findByRecordId(recordId).orElseThrow(
                () -> new IllegalStateException("artefacto fiscal no encontrado"));
        return claimBatchLocked(record.getCompanyId(), record.getInstallationId(),
                artifact.getEnvironment(), maximum, recordId);
    }

    /** Manual retry claim that participates in the same scope lease as batches. */
    @Transactional
    public Optional<ClaimedFiscalBatch> claimManualRetryBatch(
            UUID recordId, long expectedVersion, int maximum) {
        if (flows == null || artifacts == null) return Optional.empty();
        if (recordId == null || maximum < 1 || maximum > 1000) {
            throw new IllegalArgumentException("Reintento fiscal invalido");
        }
        var now = Instant.now(clock);
        var initialRecord = records.findById(recordId).orElseThrow(
                () -> new NoSuchElementException("Registro fiscal no encontrado"));
        requireVerifactu(initialRecord);
        var currentStore = organization.currentStore();
        var currentCompany = organization.currentCompany();
        if (currentStore == null || currentCompany == null
                || !initialRecord.getStoreId().equals(currentStore.getId())
                || !initialRecord.getCompanyId().equals(currentCompany.getId())) {
            throw new NoSuchElementException("Registro fiscal no encontrado");
        }
        if (installations != null && licenses != null) {
            var installation = FiscalInstallationResolver.resolveCurrent(
                    organization, installations, licenses);
            if (!installation.getId().equals(initialRecord.getInstallationId())) {
                throw new NoSuchElementException("Registro fiscal no encontrado");
            }
        }
        var artifact = artifacts.findByRecordId(recordId).orElseThrow(
                () -> new IllegalStateException("artefacto fiscal no encontrado"));
        var scope = flows.findForUpdate(initialRecord.getCompanyId(), initialRecord.getInstallationId(),
                        artifact.getEnvironment())
                .orElseGet(() -> createFlow(initialRecord.getCompanyId(), initialRecord.getInstallationId(),
                        artifact.getEnvironment()));
        if (!scope.available(now)) return Optional.empty();
        if (scope.getNextAllowedAt() != null && scope.getNextAllowedAt().isAfter(now)) {
            return Optional.empty();
        }
        var record = records.findById(recordId).orElseThrow(
                () -> new NoSuchElementException("Registro fiscal no encontrado"));
        var lockedArtifact = artifacts.findByRecordId(recordId).orElseThrow(
                () -> new IllegalStateException("artefacto fiscal no encontrado"));
        if (!record.getCompanyId().equals(scope.getCompanyId())
                || !record.getInstallationId().equals(scope.getInstallationId())
                || record.getFiscalMode() != FiscalMode.VERIFACTU
                || lockedArtifact.getEnvironment() != scope.getEnvironment()) {
            throw new NoSuchElementException("Registro fiscal no encontrado");
        }
        if (states.hasAnyNonTerminalPredecessor(recordId)) return Optional.empty();
        var state = states.findForUpdate(recordId).orElseThrow(
                () -> new NoSuchElementException("Estado de envio fiscal no encontrado"));
        if (state.getVersion() != expectedVersion) {
            throw new IllegalStateException(
                    "El estado fiscal cambio; actualiza los datos antes de reintentar");
        }
        if (!manuallyRetryable(state)) {
            throw new IllegalStateException(
                    "El registro fiscal no admite reintento manual en estado " + state.getStatus());
        }
        var owner = UUID.randomUUID();
        var until = now.plus(CLAIM_LEASE);
        state.claimManual(owner, UUID.randomUUID(), now, until);
        scope.claim(owner, now, until);
        flows.save(scope);
        return Optional.of(new ClaimedFiscalBatch(scope,
                List.of(new ClaimedFiscalSubmission(record, states.save(state)))));
    }

    /**
     * Claims one AEAT batch under a durable scope lease. The scope row is
     * locked before the queue rows, so two nodes cannot bypass pacing or claim
     * the same batch. A pacing bypass is allowed only for a full request of
     * 1000 when at least 1000 candidates are due; the selected batch remains exact.
     */
    @Transactional
    public Optional<ClaimedFiscalBatch> claimBatch(
            UUID companyId, UUID installationId, FiscalEndpointEnvironment environment,
            int maximum) {
        if (flows == null) {
            throw new IllegalStateException("El control de flujo fiscal no esta configurado");
        }
        if (companyId == null || installationId == null || environment == null) {
            throw new IllegalArgumentException("scope fiscal incompleto");
        }
        if (maximum < 1 || maximum > 1000) {
            throw new IllegalArgumentException("El lote AEAT debe tener entre 1 y 1000 registros");
        }
        return claimBatchLocked(companyId, installationId, environment, maximum, null);
    }

    /**
     * Scope lock is deliberately acquired before any state row is selected.
     * The optional target is checked against that same locked snapshot, which
     * prevents manual dispatch from overtaking a newly claimable predecessor.
     */
    private Optional<ClaimedFiscalBatch> claimBatchLocked(
            UUID companyId, UUID installationId, FiscalEndpointEnvironment environment,
            int maximum, UUID requiredRecordId) {
        if (companyId == null || installationId == null || environment == null) {
            throw new IllegalArgumentException("scope fiscal incompleto");
        }
        if (maximum < 1 || maximum > 1000) {
            throw new IllegalArgumentException("El lote AEAT debe tener entre 1 y 1000 registros");
        }
        var now = Instant.now(clock);
        var scope = flows.findForUpdate(companyId, installationId, environment)
                .orElseGet(() -> createFlow(companyId, installationId, environment));
        if (!scope.available(now)) return Optional.empty();
        if (requiredRecordId != null) {
            // Re-read the target only after the scope lock. The caller's
            // initial artifact lookup is solely discovery of the environment.
            var target = records.findByIdAndCompanyIdAndInstallationId(
                            requiredRecordId, companyId, installationId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Registro fiscal no encontrado"));
            requireVerifactu(target);
            var targetArtifact = artifacts.findByRecordId(requiredRecordId)
                    .orElseThrow(() -> new IllegalStateException(
                            "artefacto fiscal no encontrado"));
            if (targetArtifact.getEnvironment() != environment) {
                throw new IllegalStateException("artefacto fiscal no encontrado");
            }
        }
        boolean paced = scope.getNextAllowedAt() != null
                && scope.getNextAllowedAt().isAfter(now);
        // A pacing bypass is an explicit full AEAT request. Smaller caller
        // requests never bypass pacing, and backlog above 1000 is allowed.
        if (paced && maximum != 1000) return Optional.empty();
        if (paced && states.countClaimableBatch(
                companyId, installationId, environment.name(), now) < 1000) {
            return Optional.empty();
        }
        var selected = states.findClaimableBatch(
                companyId, installationId, environment.name(), now, maximum);
        if (selected == null || selected.isEmpty()
                || selected.size() > maximum
                || (paced && selected.size() != 1000)) return Optional.empty();
        if (requiredRecordId != null
                && !requiredRecordId.equals(selected.getFirst().getRecordId())) {
            return Optional.empty();
        }
        var owner = UUID.randomUUID();
        var until = now.plus(CLAIM_LEASE);
        var selectedIds = selected.stream().map(FiscalSubmissionState::getRecordId).toList();
        var recordsById = records.findByCompanyIdAndInstallationIdAndIdInOrderBySequenceAsc(
                        companyId, installationId, selectedIds).stream()
                .collect(java.util.stream.Collectors.toMap(FiscalRecord::getId, value -> value));
        var artifactsById = artifacts.findAllByRecordIdIn(selectedIds).stream()
                .collect(java.util.stream.Collectors.toMap(FiscalRecordArtifact::getRecordId, value -> value));
        var claimed = new java.util.ArrayList<ClaimedFiscalSubmission>(selected.size());
        for (var state : selected) {
            var record = java.util.Optional.ofNullable(recordsById.get(state.getRecordId()))
                    .orElseThrow(() -> new IllegalStateException("registro fiscal no encontrado"));
            requireVerifactu(record);
            if (!environment.name().equals(java.util.Optional.ofNullable(
                    artifactsById.get(record.getId())).map(a -> a.getEnvironment().name()).orElse(null))) {
                throw new IllegalStateException("artefacto fiscal no encontrado");
            }
            state.claim(owner, UUID.randomUUID(), now, until);
            claimed.add(new ClaimedFiscalSubmission(record, states.save(state)));
        }
        scope.claim(owner, now, until);
        flows.save(scope);
        return Optional.of(new ClaimedFiscalBatch(scope, claimed));
    }

    private FiscalSubmissionScopeFlow createFlow(
            UUID companyId, UUID installationId, FiscalEndpointEnvironment environment) {
        flows.insertIfMissing(UUID.randomUUID(), companyId, installationId, environment.name());
        return flows.findForUpdate(companyId, installationId, environment)
                .orElseThrow(() -> new IllegalStateException("No se pudo crear el scope fiscal"));
    }

    // Returns retryable records for the current store in queue order.
    @Transactional(readOnly = true)
    public List<FiscalSubmissionQueueItem> pending() {
        if (!legacyFallback) {
            var companyId = organization.currentCompany().getId();
            var storeId = organization.currentStore().getId();
            var installationId = installations == null || licenses == null
                    ? null
                    : FiscalInstallationResolver.resolveCurrent(
                            organization, installations, licenses).getId();
            return (installationId == null
                    ? states.findAdminQueueItems(
                            companyId, storeId, VISIBLE_IN_QUEUE,
                            PageRequest.of(0, MAX_LEGACY_QUEUE_SIZE))
                    : states.findAdminQueueItems(
                            companyId, storeId, installationId, VISIBLE_IN_QUEUE,
                            PageRequest.of(0, MAX_LEGACY_QUEUE_SIZE)));
        }
        return visibleInQueue().stream()
                .map(item -> FiscalSubmissionQueueItem.from(item.record(), item.state()))
                .limit(MAX_LEGACY_QUEUE_SIZE)
                .toList();
    }

    // Reclama el primer registro disponible para evitar envios duplicados.
    @Transactional
    public Optional<ClaimedFiscalSubmission> claimNext() {
        if (batchCoordinatorAvailable()) {
            return claimNextBatch(1).map(batch -> batch.submissions().getFirst());
        }
        var now = Instant.now(clock);
        var atomic = states.findClaimable(now, 1);
        if (atomic != null && !atomic.isEmpty()) {
            return claimSelected(atomic.getFirst(), now);
        }
        // Compatibility fallback for pre-V220 focused unit doubles. Production
        // candidates are selected by the atomic PostgreSQL query above.
        if (!legacyFallback) {
            return Optional.empty();
        }
        return automaticallyRetryableAcrossStores().stream()
                .filter(item -> eligible(item.state()))
                .map(item -> claim(item.record().getId()))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Claims one pending record for an explicitly validated fiscal scope. */
    @Transactional
    public Optional<ClaimedFiscalSubmission> claimPendingForScope(
            UUID companyId, UUID installationId, UUID recordId) {
        if (companyId == null || installationId == null) {
            throw new IllegalArgumentException(
                    "companyId e installationId son obligatorios");
        }
        if (batchCoordinatorAvailable()) {
            if (recordId != null) {
                return claimBatchForRecord(companyId, installationId, recordId, 1)
                        .map(batch -> batch.submissions().getFirst());
            }
            var now = Instant.now(clock);
            var environments = states.findClaimableEnvironmentsForScope(
                    companyId, installationId, now);
            for (var value : environments) {
                var environment = FiscalEndpointEnvironment.valueOf(value);
                var claimed = claimBatch(companyId, installationId, environment, 1);
                if (claimed.isPresent()) return claimed.map(batch -> batch.submissions().getFirst());
            }
            return Optional.empty();
        }
        var now = Instant.now(clock);
        if (recordId != null) {
            var record = records.findByIdAndCompanyIdAndInstallationId(
                            recordId, companyId, installationId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Registro fiscal no encontrado"));
            requireVerifactu(record);
            var first = states.findPendingClaimableForScope(
                    companyId, installationId, now, 1);
            if (first == null || first.isEmpty()
                    || !recordId.equals(first.getFirst().getRecordId())) {
                return Optional.empty();
            }
            var state = states.findForUpdate(recordId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Estado de envio fiscal no encontrado"));
            if (state.getStatus() != FiscalSubmissionStatus.PENDIENTE) {
                return Optional.empty();
            }
            state.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plus(CLAIM_LEASE));
            return Optional.of(new ClaimedFiscalSubmission(record, states.save(state)));
        }
        var selected = states.findPendingClaimableForScope(
                companyId, installationId, now, 1);
        if (selected == null || selected.isEmpty()) {
            return Optional.empty();
        }
        var state = selected.getFirst();
        var record = records.findByIdAndCompanyIdAndInstallationId(
                        state.getRecordId(), companyId, installationId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Registro fiscal no encontrado"));
        requireVerifactu(record);
        state.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plus(CLAIM_LEASE));
        return Optional.of(new ClaimedFiscalSubmission(record, states.save(state)));
    }

    @Transactional
    public Optional<ClaimedFiscalSubmission> claim(UUID recordId) {
        if (batchCoordinatorAvailable()) {
            return claimBatchForRecord(recordId, 1)
                    .map(batch -> batch.submissions().getFirst());
        }
        var record = records.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("registro fiscal no encontrado"));
        requireVerifactu(record);
        var state = states.findForUpdate(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
        if (!eligible(state)) {
            return Optional.empty();
        }
        var now = Instant.now(clock);
        if (state.getStatus() == FiscalSubmissionStatus.ENVIANDO
                && state.getLeaseUntil() == null) {
            // Legacy in-memory entities used by old callers had no lease field.
            state.mark(FiscalSubmissionStatus.ENVIANDO, now);
        } else {
            state.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plus(CLAIM_LEASE));
        }
        return Optional.of(new ClaimedFiscalSubmission(record, states.save(state)));
    }
    // Reclama un registro concreto tras el commit sin competir con otro worker.

    @Transactional
    public ClaimedFiscalSubmission claimForManualRetry(UUID recordId, long expectedVersion) {
        if (batchCoordinatorAvailable()) {
            return claimManualRetryBatch(recordId, expectedVersion, 1)
                    .orElseThrow(() -> new IllegalStateException(
                            "El scope fiscal esta ocupado o la cadena no esta disponible"))
                    .submissions().getFirst();
        }
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var record = records.findByIdAndCompanyIdAndStoreId(recordId, companyId, storeId)
                .orElseThrow(() -> new NoSuchElementException("Registro fiscal no encontrado"));
        if (installations != null && licenses != null) {
            var installation = FiscalInstallationResolver.resolveCurrent(
                    organization, installations, licenses);
            if (!installation.getId().equals(record.getInstallationId())) {
                throw new NoSuchElementException("Registro fiscal no encontrado");
            }
        }
        requireVerifactu(record);
        var state = states.findForUpdate(recordId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Estado de envio fiscal no encontrado"));
        if (state.getVersion() != expectedVersion) {
            throw new IllegalStateException(
                    "El estado fiscal cambio; actualiza los datos antes de reintentar");
        }
        if (!manuallyRetryable(state)) {
            throw new IllegalStateException(
                    "El registro fiscal no admite reintento manual en estado "
                            + state.getStatus());
        }
        var now = Instant.now(clock);
        state.claimManual(UUID.randomUUID(), UUID.randomUUID(), now, now.plus(CLAIM_LEASE));
        return new ClaimedFiscalSubmission(record, states.save(state));
    }

    private Optional<ClaimedFiscalSubmission> claimSelected(
            FiscalSubmissionState state, Instant now) {
        var record = records.findById(state.getRecordId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "registro fiscal no encontrado"));
        requireVerifactu(record);
        state.claim(UUID.randomUUID(), UUID.randomUUID(), now, now.plus(CLAIM_LEASE));
        return Optional.of(new ClaimedFiscalSubmission(record, states.save(state)));
    }

    private boolean manuallyRetryable(FiscalSubmissionState state) {
        if (state.getStatus() == FiscalSubmissionStatus.ENVIADO) {
            return true;
        }
        return state.getStatus() == FiscalSubmissionStatus.DEFECTUOSO
                && defects.classify(state.getLastErrorCode())
                == VerifactuDefectKind.RETRYABLE_TECHNICAL;
    }

    private List<QueueCandidate> visibleInQueue() {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var installationId = installations == null || licenses == null
                ? null
                : FiscalInstallationResolver.resolveCurrent(
                        organization, installations, licenses).getId();
        return states.findAllByStatusInOrderByUpdatedAtAsc(VISIBLE_IN_QUEUE).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
                        .filter(record -> record.getCompanyId().equals(companyId))
                        .filter(record -> record.getStoreId().equals(storeId))
                        .filter(record -> installationId == null
                                || installationId.equals(record.getInstallationId()))
                        .filter(this::isVerifactuRecord)
                        .map(record -> new QueueCandidate(record, state)))
                .toList();
    }

    private List<QueueCandidate> automaticallyRetryableAcrossStores() {
        return states.findAllByStatusInOrderByUpdatedAtAsc(AUTOMATICALLY_RETRYABLE).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
                        .filter(this::isVerifactuRecord)
                        .map(record -> new QueueCandidate(record, state)))
                .toList();
    }
    // The worker scans every store; the administrative view stays scoped.

    private boolean eligible(FiscalSubmissionState state) {
        if (state.getStatus() == FiscalSubmissionStatus.PENDIENTE) {
            return true;
        }
        return AUTOMATICALLY_RETRYABLE.contains(state.getStatus())
                && !state.getUpdatedAt().isAfter(Instant.now(clock).minus(RETRY_DELAY));
    }

    private boolean isVerifactuRecord(FiscalRecord record) {
        return record != null && record.getFiscalMode() == FiscalMode.VERIFACTU;
    }

    private void requireVerifactu(FiscalRecord record) {
        if (!isVerifactuRecord(record)) {
            throw new IllegalArgumentException(
                    "Solo se pueden enviar registros fiscales VERI*FACTU");
        }
    }

    private record QueueCandidate(FiscalRecord record, FiscalSubmissionState state) {
    }
}

package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalSubmissionQueueService {

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

    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordRepository records;
    private final CurrentOrganization organization;
    private final Clock clock;
    private final VerifactuDefectClassifier defects;

    public FiscalSubmissionQueueService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock,
            VerifactuDefectClassifier defects) {
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.clock = clock;
        this.defects = defects;
    }

    // Returns retryable records for the current store in queue order.
    @Transactional(readOnly = true)
    public List<FiscalSubmissionQueueItem> pending() {
        return visibleInQueue().stream()
                .map(item -> FiscalSubmissionQueueItem.from(item.record(), item.state()))
                .toList();
    }

    // Reclama el primer registro disponible para evitar envios duplicados.
    @Transactional
    public Optional<ClaimedFiscalSubmission> claimNext() {
        return automaticallyRetryableAcrossStores().stream()
                .filter(item -> eligible(item.state()))
                .map(item -> claim(item.record().getId()))
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Transactional
    public Optional<ClaimedFiscalSubmission> claim(UUID recordId) {
        var record = records.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("registro fiscal no encontrado"));
        var state = states.findForUpdate(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
        if (!eligible(state)) {
            return Optional.empty();
        }
        state.mark(FiscalSubmissionStatus.ENVIANDO, Instant.now(clock));
        return Optional.of(new ClaimedFiscalSubmission(record, states.save(state)));
    }
    // Reclama un registro concreto tras el commit sin competir con otro worker.

    @Transactional
    public ClaimedFiscalSubmission claimForManualRetry(UUID recordId, long expectedVersion) {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var record = records.findByIdAndCompanyIdAndStoreId(recordId, companyId, storeId)
                .orElseThrow(() -> new NoSuchElementException("Registro fiscal no encontrado"));
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
        state.mark(FiscalSubmissionStatus.ENVIANDO, Instant.now(clock));
        return new ClaimedFiscalSubmission(record, states.save(state));
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
        return states.findAllByStatusInOrderByUpdatedAtAsc(VISIBLE_IN_QUEUE).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
                        .filter(record -> record.getCompanyId().equals(companyId))
                        .filter(record -> record.getStoreId().equals(storeId))
                        .map(record -> new QueueCandidate(record, state)))
                .toList();
    }

    private List<QueueCandidate> automaticallyRetryableAcrossStores() {
        return states.findAllByStatusInOrderByUpdatedAtAsc(AUTOMATICALLY_RETRYABLE).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
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

    private record QueueCandidate(FiscalRecord record, FiscalSubmissionState state) {
    }
}

package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalSubmissionQueueService {

    private static final List<FiscalSubmissionStatus> RETRYABLE = List.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIANDO,
            FiscalSubmissionStatus.ENVIADO,
            FiscalSubmissionStatus.RECHAZADO);
    private static final java.time.Duration RETRY_DELAY = java.time.Duration.ofHours(1);

    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordRepository records;
    private final CurrentOrganization organization;
    private final Clock clock;

    public FiscalSubmissionQueueService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock) {
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.clock = clock;
    }

    // Devuelve registros reintentables de la tienda actual en orden de cola.
    @Transactional(readOnly = true)
    public List<FiscalSubmissionQueueItem> pending() {
        return retryable().stream()
                .map(item -> FiscalSubmissionQueueItem.from(item.record(), item.state()))
                .toList();
    }

    // Reclama el primer registro disponible para evitar envios duplicados.
    @Transactional
    public Optional<ClaimedFiscalSubmission> claimNext() {
        return retryableAcrossStores().stream()
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

    private List<QueueCandidate> retryable() {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        return states.findAllByStatusInOrderByUpdatedAtAsc(RETRYABLE).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
                        .filter(record -> record.getCompanyId().equals(companyId))
                        .filter(record -> record.getStoreId().equals(storeId))
                        .map(record -> new QueueCandidate(record, state)))
                .toList();
    }

    private List<QueueCandidate> retryableAcrossStores() {
        return states.findAllByStatusInOrderByUpdatedAtAsc(RETRYABLE).stream()
                .flatMap(state -> records.findById(state.getRecordId()).stream()
                        .map(record -> new QueueCandidate(record, state)))
                .toList();
    }
    // El worker recorre todas las tiendas; la vista administrativa permanece acotada.

    private boolean eligible(FiscalSubmissionState state) {
        if (state.getStatus() == FiscalSubmissionStatus.PENDIENTE) {
            return true;
        }
        return RETRYABLE.contains(state.getStatus())
                && !state.getUpdatedAt().isAfter(Instant.now(clock).minus(RETRY_DELAY));
    }

    private record QueueCandidate(FiscalRecord record, FiscalSubmissionState state) {
    }
}

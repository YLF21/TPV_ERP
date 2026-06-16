package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalSubmissionQueueService {

    private static final List<FiscalSubmissionStatus> RETRYABLE = List.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIADO);

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
        return retryable().stream().findFirst().map(item -> {
            var state = states.findById(item.state().getRecordId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "estado de envio fiscal no encontrado"));
            state.mark(FiscalSubmissionStatus.ENVIANDO, Instant.now(clock));
            return new ClaimedFiscalSubmission(item.record(), states.save(state));
        });
    }

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

    private record QueueCandidate(FiscalRecord record, FiscalSubmissionState state) {
    }
}

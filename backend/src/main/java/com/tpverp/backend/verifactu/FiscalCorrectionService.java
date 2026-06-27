package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalCorrectionService {

    private static final EnumSet<FiscalSubmissionStatus> CORRECTABLE = EnumSet.of(
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.DEFECTUOSO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);

    private final FiscalRecordRepository records;
    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordService fiscalRecords;
    private final FiscalCorrectionSnapshot snapshots;
    private final CurrentOrganization organization;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FiscalCorrectionService(
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states,
            FiscalRecordService fiscalRecords,
            FiscalCorrectionSnapshot snapshots,
            CurrentOrganization organization,
            ApplicationEventPublisher events,
            Clock clock) {
        this.records = records;
        this.states = states;
        this.fiscalRecords = fiscalRecords;
        this.snapshots = snapshots;
        this.organization = organization;
        this.events = events;
        this.clock = clock;
    }

    // Creates an isolated correction per tenant and schedules submission after transaction commit.
    @Transactional
    public FiscalCorrectionView correct(
            UUID recordId,
            FiscalCorrectionRequest request,
            Authentication authentication) {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var original = records.findByIdAndCompanyIdAndStoreId(recordId, companyId, storeId)
                .orElseThrow(() -> new IllegalArgumentException("registro fiscal no encontrado"));
        var state = states.findForUpdate(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
        if (!CORRECTABLE.contains(state.getStatus())) {
            throw new IllegalStateException("El registro fiscal no admite subsanacion");
        }
        var correctedAt = Instant.now(clock);
        var snapshot = snapshots.apply(
                original.getSnapshot(), request, original.getId(),
                organization.currentUser(authentication).getId(), correctedAt,
                state.getStatus() == FiscalSubmissionStatus.RECHAZADO);
        var correction = fiscalRecords.registerCorrection(original, snapshot);
        events.publishEvent(new FiscalRecordQueuedEvent(correction.getId()));
        return FiscalCorrectionView.pending(correction, original.getId());
    }
}

package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalSubmissionStateService {

    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordRepository records;
    private final CurrentOrganization organization;
    private final Clock clock;

    public FiscalSubmissionStateService(
            FiscalSubmissionStateRepository states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock) {
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional
    public FiscalSubmissionState markSending(UUID recordId) {
        return mark(recordId, FiscalSubmissionStatus.ENVIANDO);
    }

    @Transactional
    public FiscalSubmissionState markSent(UUID recordId) {
        return mark(recordId, FiscalSubmissionStatus.ENVIADO);
    }

    @Transactional
    public FiscalSubmissionState markAccepted(UUID recordId) {
        return mark(recordId, FiscalSubmissionStatus.ACEPTADO);
    }

    @Transactional
    public FiscalSubmissionState markAcceptedWithErrors(
            UUID recordId, String errorCode, String error) {
        return markIncident(
                recordId, FiscalSubmissionStatus.ACEPTADO_CON_ERRORES, errorCode, error);
    }

    @Transactional
    public FiscalSubmissionState markRejected(
            UUID recordId, String errorCode, String error) {
        return markIncident(recordId, FiscalSubmissionStatus.RECHAZADO, errorCode, error);
    }

    @Transactional
    public FiscalSubmissionState markDefective(
            UUID recordId, String errorCode, String error) {
        return markIncident(recordId, FiscalSubmissionStatus.DEFECTUOSO, errorCode, error);
    }

    protected FiscalSubmissionState mark(UUID recordId, FiscalSubmissionStatus status) {
        var state = state(recordId);
        state.mark(status, now());
        return states.save(state);
    }

    protected FiscalSubmissionState markIncident(
            UUID recordId,
            FiscalSubmissionStatus status,
            String errorCode,
            String error) {
        var state = state(recordId);
        state.markIncident(status, errorCode, error, now());
        return states.save(state);
    }

    private FiscalSubmissionState state(UUID recordId) {
        validateCurrentScope(recordId);
        return states.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
    }

    private void validateCurrentScope(UUID recordId) {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var record = records.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "registro fiscal no encontrado"));
        if (!record.getCompanyId().equals(companyId) || !record.getStoreId().equals(storeId)) {
            throw new IllegalArgumentException(
                    "registro fiscal no pertenece a la tienda actual");
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }
}

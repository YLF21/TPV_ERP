package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalSubmissionAttemptService {

    private final FiscalSubmissionAttemptRepository attempts;
    private final FiscalSubmissionStateService states;
    private final FiscalRecordRepository records;
    private final CurrentOrganization organization;
    private final Clock clock;

    public FiscalSubmissionAttemptService(
            FiscalSubmissionAttemptRepository attempts,
            FiscalSubmissionStateService states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock) {
        this.attempts = attempts;
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.clock = clock;
    }

    // Guarda el XML enviado y marca el registro como enviado para reintento.
    @Transactional
    public FiscalSubmissionAttempt recordSent(UUID recordId, String requestXml) {
        states.markSent(recordId);
        return save(recordId, FiscalSubmissionStatus.ENVIADO, null, null, requestXml, null);
    }

    // Guarda una aceptacion completa y limpia incidencias previas.
    @Transactional
    public FiscalSubmissionAttempt recordAccepted(UUID recordId, String responsePayload) {
        states.markAccepted(recordId);
        return save(recordId, FiscalSubmissionStatus.ACEPTADO, null, null, null, responsePayload);
    }

    // Guarda una aceptacion con errores visible en el apartado de defectuosos.
    @Transactional
    public FiscalSubmissionAttempt recordAcceptedWithErrors(
            UUID recordId, String errorCode, String error, String responsePayload) {
        errorCode = required(errorCode, "codigo de error");
        error = required(error, "error");
        states.markAcceptedWithErrors(recordId, errorCode, error);
        return save(recordId, FiscalSubmissionStatus.ACEPTADO_CON_ERRORES,
                errorCode, error, null, responsePayload);
    }

    // Guarda un rechazo AEAT sin bloquear ventas nuevas.
    @Transactional
    public FiscalSubmissionAttempt recordRejected(
            UUID recordId, String errorCode, String error, String responsePayload) {
        errorCode = required(errorCode, "codigo de error");
        error = required(error, "error");
        states.markRejected(recordId, errorCode, error);
        return save(recordId, FiscalSubmissionStatus.RECHAZADO,
                errorCode, error, null, responsePayload);
    }

    // Guarda un error interno de datos/campo para revision administrativa.
    @Transactional
    public FiscalSubmissionAttempt recordDefective(
            UUID recordId, String errorCode, String error, String responsePayload) {
        errorCode = required(errorCode, "codigo de error");
        error = required(error, "error");
        states.markDefective(recordId, errorCode, error);
        return save(recordId, FiscalSubmissionStatus.DEFECTUOSO,
                errorCode, error, null, responsePayload);
    }

    @Transactional(readOnly = true)
    public List<FiscalSubmissionAttempt> history(UUID recordId) {
        records.findByIdAndCompanyIdAndStoreId(
                        recordId,
                        organization.currentCompany().getId(),
                        organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "registro fiscal no encontrado"));
        return attempts.findAllByRecordIdOrderByAttemptedAtDesc(recordId);
    }

    private FiscalSubmissionAttempt save(
            UUID recordId,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            String requestXml,
            String responsePayload) {
        return attempts.save(new FiscalSubmissionAttempt(
                recordId, Instant.now(clock), status,
                errorCode, error, requestXml, responsePayload));
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }
}

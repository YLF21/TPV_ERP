package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
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
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final boolean legacyHistoryFallback;

    public FiscalSubmissionAttemptService(
            FiscalSubmissionAttemptRepository attempts,
            FiscalSubmissionStateService states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock) {
        this(attempts, states, records, organization, clock, null, null, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FiscalSubmissionAttemptService(
            FiscalSubmissionAttemptRepository attempts,
            FiscalSubmissionStateService states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock,
            InstallationRepository installations,
            LicenseRepository licenses) {
        this(attempts, states, records, organization, clock, installations, licenses, false);
    }

    private FiscalSubmissionAttemptService(
            FiscalSubmissionAttemptRepository attempts,
            FiscalSubmissionStateService states,
            FiscalRecordRepository records,
            CurrentOrganization organization,
            Clock clock,
            InstallationRepository installations,
            LicenseRepository licenses,
            boolean legacyHistoryFallback) {
        this.attempts = attempts;
        this.states = states;
        this.records = records;
        this.organization = organization;
        this.clock = clock;
        this.installations = installations;
        this.licenses = licenses;
        this.legacyHistoryFallback = legacyHistoryFallback;
    }

    // Guarda el XML enviado y marca el registro como enviado para reintento.
    @Transactional
    public FiscalSubmissionAttempt recordSent(UUID recordId, String requestXml) {
        states.markSent(recordId);
        return save(recordId, FiscalSubmissionStatus.ENVIADO, null, null, requestXml, null);
    }

    /** Persists the exact request before transport without releasing the claim lease. */
    @Transactional
    public FiscalSubmissionAttempt recordRequest(
            UUID recordId, String requestXml, UUID claimToken) {
        return recordRequest(recordId, requestXml, claimToken, null);
    }

    /** Persists a line reference without duplicating the batch request. */
    @Transactional
    public FiscalSubmissionAttempt recordRequest(
            UUID recordId, String requestXml, UUID claimToken, UUID evidenceId) {
        states.requireClaim(recordId, claimToken);
        return save(recordId, FiscalSubmissionStatus.ENVIANDO, null, null,
                evidenceId == null ? requestXml : null, null, evidenceId);
    }

    @Transactional
    public FiscalSubmissionAttempt recordTransportFailure(
            UUID recordId, String errorCode, String error, String requestXml, UUID claimToken) {
        return recordTransportFailure(recordId, errorCode, error, requestXml, null, claimToken);
    }

    @Transactional
    public FiscalSubmissionAttempt recordTransportFailure(
            UUID recordId, String errorCode, String error, String requestXml,
            String responsePayload, UUID claimToken) {
        return recordTransportFailure(recordId, errorCode, error, requestXml, responsePayload,
                claimToken, null);
    }

    @Transactional
    public FiscalSubmissionAttempt recordTransportFailure(
            UUID recordId, String errorCode, String error, String requestXml,
            String responsePayload, UUID claimToken, UUID evidenceId) {
        errorCode = required(errorCode, "codigo de error");
        error = required(error, "error");
        states.markTransportFailure(recordId, errorCode, error, claimToken);
        return save(recordId, FiscalSubmissionStatus.ENVIADO,
                errorCode, error, evidenceId == null ? requestXml : null,
                evidenceId == null ? responsePayload : null, evidenceId);
    }

    // Guarda una aceptacion completa y limpia incidencias previas.
    @Transactional
    public FiscalSubmissionAttempt recordAccepted(UUID recordId, String responsePayload) {
        states.markAccepted(recordId);
        return save(recordId, FiscalSubmissionStatus.ACEPTADO, null, null, null, responsePayload);
    }

    @Transactional
    public FiscalSubmissionAttempt recordAccepted(
            UUID recordId, String responsePayload, UUID claimToken) {
        return recordAccepted(recordId, responsePayload, claimToken, null);
    }

    @Transactional
    public FiscalSubmissionAttempt recordAccepted(
            UUID recordId, String responsePayload, UUID claimToken, UUID evidenceId) {
        states.markAccepted(recordId, claimToken);
        return save(recordId, FiscalSubmissionStatus.ACEPTADO, null, null,
                null, evidenceId == null ? responsePayload : null, evidenceId);
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

    @Transactional
    public FiscalSubmissionAttempt recordAcceptedWithErrors(
            UUID recordId, String errorCode, String error, String responsePayload,
            UUID claimToken) {
        return recordAcceptedWithErrors(recordId, errorCode, error, responsePayload, claimToken, null);
    }

    @Transactional
    public FiscalSubmissionAttempt recordAcceptedWithErrors(
            UUID recordId, String errorCode, String error, String responsePayload,
            UUID claimToken, UUID evidenceId) {
        errorCode = required(errorCode, "codigo de error");
        error = required(error, "error");
        states.markAcceptedWithErrors(recordId, errorCode, error, claimToken);
        return save(recordId, FiscalSubmissionStatus.ACEPTADO_CON_ERRORES,
                errorCode, error, null, evidenceId == null ? responsePayload : null, evidenceId);
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

    @Transactional
    public FiscalSubmissionAttempt recordRejected(
            UUID recordId, String errorCode, String error, String responsePayload,
            UUID claimToken) {
        return recordRejected(recordId, errorCode, error, responsePayload, claimToken, null);
    }

    @Transactional
    public FiscalSubmissionAttempt recordRejected(
            UUID recordId, String errorCode, String error, String responsePayload,
            UUID claimToken, UUID evidenceId) {
        errorCode = required(errorCode, "codigo de error");
        error = required(error, "error");
        states.markRejected(recordId, errorCode, error, claimToken);
        return save(recordId, FiscalSubmissionStatus.RECHAZADO,
                errorCode, error, null, evidenceId == null ? responsePayload : null, evidenceId);
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

    @Transactional
    public FiscalSubmissionAttempt recordDefective(
            UUID recordId, String errorCode, String error, String responsePayload,
            UUID claimToken) {
        return recordDefective(recordId, errorCode, error, responsePayload, claimToken, null);
    }

    @Transactional
    public FiscalSubmissionAttempt recordDefective(
            UUID recordId, String errorCode, String error, String responsePayload,
            UUID claimToken, UUID evidenceId) {
        errorCode = required(errorCode, "codigo de error");
        error = required(error, "error");
        states.markDefective(recordId, errorCode, error, claimToken);
        return save(recordId, FiscalSubmissionStatus.DEFECTUOSO,
                errorCode, error, null, evidenceId == null ? responsePayload : null, evidenceId);
    }

    @Transactional(readOnly = true)
    public List<FiscalSubmissionAttempt> history(UUID recordId) {
        var store = organization.currentStore();
        var companyId = organization.currentCompany().getId();
        var record = records.findByIdAndCompanyIdAndStoreId(
                        recordId, companyId, store.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "registro fiscal no encontrado"));
        if (installations != null && licenses != null) {
            var installation = FiscalInstallationResolver.resolveCurrent(
                    organization, installations, licenses);
            if (!installation.getId().equals(record.getInstallationId())) {
                throw new IllegalArgumentException("registro fiscal no encontrado");
            }
        }
        return (legacyHistoryFallback
                ? attempts.findAllByRecordIdOrderByAttemptedAtDesc(recordId).stream()
                        .limit(200)
                        .toList()
                : attempts.findTop200ByRecordIdOrderByAttemptedAtDesc(recordId));
    }

    private FiscalSubmissionAttempt save(
            UUID recordId,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            String requestXml,
            String responsePayload) {
        return save(recordId, status, errorCode, error, requestXml, responsePayload, null);
    }

    private FiscalSubmissionAttempt save(
            UUID recordId,
            FiscalSubmissionStatus status,
            String errorCode,
            String error,
            String requestXml,
            String responsePayload,
            UUID evidenceId) {
        return attempts.save(new FiscalSubmissionAttempt(
                recordId, Instant.now(clock), status,
                errorCode, error, requestXml, responsePayload, evidenceId));
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }
}

package com.tpverp.backend.verifactu;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VerifactuManualRetryService {

    private final FiscalSubmissionQueueService queue;
    private final VerifactuSubmissionService submissions;
    private final AuditService audit;

    public VerifactuManualRetryService(
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionService submissions,
            AuditService audit) {
        this.queue = queue;
        this.submissions = submissions;
        this.audit = audit;
    }

    public VerifactuManualRetryView retry(
            UUID recordId,
            VerifactuManualRetryRequest request) {
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("La version esperada es obligatoria");
        }
        var reason = normalizeReason(request.reason());
        final ClaimedFiscalSubmission claimed;
        try {
            claimed = queue.claimForManualRetry(recordId, request.expectedVersion());
        } catch (RuntimeException exception) {
            audit.record(
                    "VERIFACTU_MANUAL_RETRY_REJECTED",
                    AuditResult.FALLO,
                    Map.of(
                            "recordId", recordId.toString(),
                            "reason", reason,
                            "cause", exception.getClass().getSimpleName()));
            throw exception;
        }

        try {
            var result = submissions.submit(claimed.record());
            audit.record(
                    "VERIFACTU_MANUAL_RETRY_EXECUTED",
                    AuditResult.EXITO,
                    successDetails(recordId, reason, result));
            return new VerifactuManualRetryView(
                    recordId, result.status(), result.errorCode());
        } catch (RuntimeException exception) {
            audit.record(
                    "VERIFACTU_MANUAL_RETRY_FAILED",
                    AuditResult.FALLO,
                    Map.of(
                            "recordId", recordId.toString(),
                            "reason", reason,
                            "cause", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private static Map<String, Object> successDetails(
            UUID recordId,
            String reason,
            VerifactuSubmissionResult result) {
        var details = new LinkedHashMap<String, Object>();
        details.put("recordId", recordId.toString());
        details.put("reason", reason);
        details.put("resultStatus", result.status().name());
        if (result.errorCode() != null) {
            details.put("errorCode", result.errorCode());
        }
        return details;
    }

    private static String normalizeReason(String value) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("El motivo del reintento es obligatorio");
        }
        if (normalized.length() > 500) {
            throw new IllegalArgumentException(
                    "El motivo del reintento no puede superar 500 caracteres");
        }
        return normalized;
    }
}

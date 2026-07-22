package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuResolutionPolicyService {

    private final CurrentOrganization organization;
    private final FiscalRecordRepository records;
    private final FiscalSubmissionStateRepository states;
    private final VerifactuDefectClassifier defects;

    public VerifactuResolutionPolicyService(
            CurrentOrganization organization,
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states,
            VerifactuDefectClassifier defects) {
        this.organization = organization;
        this.records = records;
        this.states = states;
        this.defects = defects;
    }

    @Transactional(readOnly = true)
    public VerifactuResolutionView resolution(UUID recordId, Authentication authentication) {
        var store = organization.currentStore();
        var record = records.findByIdAndCompanyIdAndStoreId(
                        recordId, store.getEmpresa().getId(), store.getId())
                .orElseThrow(() -> new NoSuchElementException("Registro fiscal no encontrado"));
        var state = states.findById(recordId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Estado de envio fiscal no encontrado"));
        var decision = decide(
                record.getOperation(), state.getStatus(), state.getLastErrorCode());
        return new VerifactuResolutionView(
                recordId,
                record.getOperation(),
                state.getStatus(),
                state.getVersion(),
                state.getLastErrorCode(),
                decision.category(),
                decision.action(),
                permitted(decision.action(), authentication));
    }

    private Decision decide(
            FiscalRecordOperation operation,
            FiscalSubmissionStatus status,
            String errorCode) {
        return switch (status) {
            case PENDIENTE, ENVIANDO -> new Decision(
                    VerifactuResolutionCategory.WAITING,
                    VerifactuResolutionAction.WAIT);
            case ENVIADO -> new Decision(
                    VerifactuResolutionCategory.COMMUNICATION_PENDING,
                    VerifactuResolutionAction.RETRY);
            case DEFECTUOSO -> defective(errorCode);
            case RECHAZADO -> correctOrReview(
                    operation, VerifactuResolutionCategory.AEAT_REJECTED);
            case ACEPTADO_CON_ERRORES -> correctOrReview(
                    operation, VerifactuResolutionCategory.AEAT_ACCEPTED_WITH_ERRORS);
            case ACEPTADO -> new Decision(
                    VerifactuResolutionCategory.ACCEPTED_FINAL,
                    operation == FiscalRecordOperation.ALTA
                            ? VerifactuResolutionAction.CREATE_RECTIFYING_INVOICE
                            : VerifactuResolutionAction.NONE);
            case SUBSANADO -> new Decision(
                    VerifactuResolutionCategory.CORRECTED_FINAL,
                    VerifactuResolutionAction.NONE);
        };
    }

    private Decision defective(String errorCode) {
        return switch (defects.classify(errorCode)) {
            case RETRYABLE_TECHNICAL -> new Decision(
                    VerifactuResolutionCategory.LOCAL_TECHNICAL_ERROR,
                    VerifactuResolutionAction.RETRY);
            case ADMINISTRATIVE_CORRECTABLE -> new Decision(
                    VerifactuResolutionCategory.ADMINISTRATIVE_DATA_ERROR,
                    VerifactuResolutionAction.CREATE_CORRECTION);
            case TECHNICAL_REVIEW -> new Decision(
                    VerifactuResolutionCategory.TECHNICAL_REVIEW,
                    VerifactuResolutionAction.TECHNICAL_REVIEW);
        };
    }

    private static Decision correctOrReview(
            FiscalRecordOperation operation,
            VerifactuResolutionCategory category) {
        if (operation == FiscalRecordOperation.ALTA) {
            return new Decision(category, VerifactuResolutionAction.CREATE_CORRECTION);
        }
        return new Decision(
                VerifactuResolutionCategory.TECHNICAL_REVIEW,
                VerifactuResolutionAction.TECHNICAL_REVIEW);
    }

    private static List<VerifactuResolutionAction> permitted(
            VerifactuResolutionAction action,
            Authentication authentication) {
        boolean admin = has(authentication, "ROLE_ADMIN");
        return switch (action) {
            case RETRY -> admin || has(authentication, "VERIFACTU_MANAGE")
                    ? List.of(action) : List.of();
            case CREATE_CORRECTION -> admin || has(authentication, "VERIFACTU_CORRECT")
                    ? List.of(action) : List.of();
            case CREATE_RECTIFYING_INVOICE -> admin || has(authentication, "GESTION_VENTAS")
                    ? List.of(action) : List.of();
            default -> List.of();
        };
    }

    private static boolean has(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(candidate -> authority.equals(candidate.getAuthority()));
    }

    private record Decision(
            VerifactuResolutionCategory category,
            VerifactuResolutionAction action) {
    }
}

package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalCorrectionService {

    private static final EnumSet<FiscalSubmissionStatus> CORRECTABLE = EnumSet.of(
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);

    private final FiscalRecordRepository records;
    private final FiscalSubmissionStateRepository states;
    private final FiscalRecordService fiscalRecords;
    private final FiscalCorrectionSnapshot snapshots;
    private final CurrentOrganization organization;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final VerifactuDefectClassifier defects;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;

    /** Compatibility constructor for focused unit tests and embedders. */
    public FiscalCorrectionService(
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states,
            FiscalRecordService fiscalRecords,
            FiscalCorrectionSnapshot snapshots,
            CurrentOrganization organization,
            ApplicationEventPublisher events,
            Clock clock,
            VerifactuDefectClassifier defects) {
        this(records, states, fiscalRecords, snapshots, organization, events, clock, defects,
                null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FiscalCorrectionService(
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states,
            FiscalRecordService fiscalRecords,
            FiscalCorrectionSnapshot snapshots,
            CurrentOrganization organization,
            ApplicationEventPublisher events,
            Clock clock,
            VerifactuDefectClassifier defects,
            InstallationRepository installations,
            LicenseRepository licenses) {
        this.records = records;
        this.states = states;
        this.fiscalRecords = fiscalRecords;
        this.snapshots = snapshots;
        this.organization = organization;
        this.events = events;
        this.clock = clock;
        this.defects = defects;
        this.installations = installations;
        this.licenses = licenses;
    }

    // Creates an isolated correction per tenant and schedules submission after transaction commit.
    @Transactional
    public FiscalCorrectionView correct(
            UUID recordId,
            FiscalCorrectionRequest request,
            Authentication authentication) {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var requested = records.findByIdAndCompanyIdAndStoreId(recordId, companyId, storeId)
                .orElseThrow(() -> new IllegalArgumentException("registro fiscal no encontrado"));
        if (requested.getOperation() != FiscalRecordOperation.ALTA) {
            throw new IllegalStateException("El registro fiscal no admite subsanacion");
        }
        var original = requested.getDocumentId() == null
                ? requested
                : records.findFirstByDocumentIdAndOperationOrderBySequenceAsc(
                        requested.getDocumentId(), FiscalRecordOperation.ALTA)
                        .orElse(requested);
        if (installations != null && licenses != null) {
            var installation = FiscalInstallationResolver.resolveCurrent(
                    organization, installations, licenses);
            if (!installation.getId().equals(original.getInstallationId())) {
                throw new IllegalArgumentException("registro fiscal no encontrado");
            }
        }
        // The original state is the serialization point for all corrections of
        // the document.  This prevents two requests from both observing a
        // rejected original before either relation is committed.
        var canonicalState = states.findForUpdate(original.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "estado de envio fiscal no encontrado"));
        var requestedState = requested.getId().equals(original.getId())
                ? canonicalState
                : states.findById(requested.getId()).orElseThrow(() ->
                        new IllegalArgumentException("estado de envio fiscal no encontrado"));
        if (!correctable(requestedState)
                && (request == null || request.idempotencyKey() == null
                        || request.idempotencyKey().isBlank())) {
            throw new IllegalStateException("El registro fiscal no admite subsanacion");
        }
        var correctedAt = Instant.now(clock);
        var target = requested.getId().equals(original.getId()) ? original : requested;
        var snapshot = snapshots.apply(
                target.getSnapshot(), request, original.getId(),
                organization.currentUser(authentication).getId(), correctedAt,
                requestedState.getStatus() == FiscalSubmissionStatus.RECHAZADO);
        var withTarget = new LinkedHashMap<>(snapshot);
        withTarget.put("subsanacionObjetivoId", target.getId().toString());
        snapshot = ImmutableJson.copy(withTarget);

        var existing = existingCorrection(original, snapshot);
        if (existing != null) {
            return existing;
        }
        if (!correctable(requestedState)) {
            throw new IllegalStateException("El registro fiscal no admite subsanacion");
        }
        var correction = fiscalRecords.registerCorrection(original, snapshot);
        events.publishEvent(new FiscalRecordQueuedEvent(correction.getId()));
        return FiscalCorrectionView.pending(correction, original.getId());
    }

    private FiscalCorrectionView existingCorrection(
            FiscalRecord original, Map<String, Object> requestedSnapshot) {
        var key = text(requestedSnapshot.get("subsanacionIdempotencyKey"));
        var existing = key == null
                ? java.util.Optional.<FiscalRecord>empty()
                : records.findCorrectionByOriginalIdAndIdempotencyKey(original.getId(), key);
        if (existing.isPresent()) {
            return recoverOrConflict(existing.get(), original, requestedSnapshot);
        }

        var latest = records.findLatestCorrectionByOriginalId(original.getId()).orElse(null);
        if (latest == null) {
            return null;
        }
        var latestStateEntity = states.findById(latest.getId()).orElse(null);
        var latestState = latestStateEntity == null
                ? FiscalSubmissionStatus.PENDIENTE : latestStateEntity.getStatus();
        if (sameIntent(latest.getSnapshot(), requestedSnapshot)
                && text(latest.getSnapshot().get("subsanacionIdempotencyKey")) == null
                && key == null) {
            return FiscalCorrectionView.from(latest, original.getId(), latestState);
        }
        if (latestState == FiscalSubmissionStatus.DEFECTUOSO
                && !latest.getId().toString().equals(
                        text(requestedSnapshot.get("subsanacionObjetivoId")))) {
            throw new FiscalCorrectionPendingConflictException();
        }
        if (isPending(latestState)) {
            throw new FiscalCorrectionPendingConflictException();
        }
        // A RECHAZADO correction is a historical attempt, so a new key and
        // payload may legitimately create a new correction.
        return null;
    }

    private FiscalCorrectionView recoverOrConflict(
            FiscalRecord existing,
            FiscalRecord original,
            Map<String, Object> requestedSnapshot) {
        if (!sameIntent(existing.getSnapshot(), requestedSnapshot)) {
            throw new FiscalCorrectionIdempotencyConflictException();
        }
        var status = states.findById(existing.getId())
                .map(FiscalSubmissionState::getStatus)
                .orElse(FiscalSubmissionStatus.PENDIENTE);
        return FiscalCorrectionView.from(existing, original.getId(), status);
    }

    private static boolean isPending(FiscalSubmissionStatus status) {
        return status == FiscalSubmissionStatus.PENDIENTE
                || status == FiscalSubmissionStatus.ENVIADO
                || status == FiscalSubmissionStatus.ENVIANDO;
    }

    private static boolean sameIntent(Map<String, Object> left, Map<String, Object> right) {
        return intent(left).equals(intent(right));
    }

    private static Map<String, Object> intent(Map<String, Object> source) {
        var customer = source.get("cliente") instanceof Map<?, ?> value
                ? value : Map.of();
        var result = new LinkedHashMap<String, Object>();
        result.put("recipientTaxId", text(customer.get("numeroDocumento")));
        result.put("recipientName", text(customer.get("nombreFiscal")));
        result.put("operationDescription", text(source.get("descripcionOperacion")));
        result.put("reason", text(source.get("subsanacionMotivo")));
        result.put("targetId", text(source.get("subsanacionObjetivoId")));
        return result;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private boolean correctable(FiscalSubmissionState state) {
        return CORRECTABLE.contains(state.getStatus())
                || state.getStatus() == FiscalSubmissionStatus.DEFECTUOSO
                && defects.classify(state.getLastErrorCode())
                == VerifactuDefectKind.ADMINISTRATIVE_CORRECTABLE;
    }
}

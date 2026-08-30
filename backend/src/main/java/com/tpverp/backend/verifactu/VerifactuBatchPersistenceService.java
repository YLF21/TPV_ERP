package com.tpverp.backend.verifactu;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary for a claimed AEAT batch. Network I/O never happens
 * here: requests and response effects are committed as one scope operation.
 */
@Service
public class VerifactuBatchPersistenceService {
    private final FiscalSubmissionScopeFlowRepository flows;
    private final FiscalSubmissionStateRepository states;
    private final FiscalSubmissionAttemptService attempts;
    private final FiscalCorrectionCompletionService corrections;
    private final FiscalSubmissionEvidenceRepository evidences;
    private final FiscalSubmissionResponseEvidenceRepository responseEvidences;
    private final VerifactuFirstSubmissionMarker firstSubmissions;
    private final Clock clock;

    public VerifactuBatchPersistenceService(
            FiscalSubmissionScopeFlowRepository flows,
            FiscalSubmissionStateRepository states,
            FiscalSubmissionAttemptService attempts,
            FiscalCorrectionCompletionService corrections,
            Clock clock) {
        this(flows, states, attempts, corrections, null, null, null, clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VerifactuBatchPersistenceService(
            FiscalSubmissionScopeFlowRepository flows,
            FiscalSubmissionStateRepository states,
            FiscalSubmissionAttemptService attempts,
            FiscalCorrectionCompletionService corrections,
            FiscalSubmissionEvidenceRepository evidences,
            FiscalSubmissionResponseEvidenceRepository responseEvidences,
            VerifactuFirstSubmissionMarker firstSubmissions,
            Clock clock) {
        this.flows = flows;
        this.states = states;
        this.attempts = attempts;
        this.corrections = corrections;
        this.evidences = evidences;
        this.responseEvidences = responseEvidences;
        this.firstSubmissions = firstSubmissions;
        this.clock = clock;
    }

    @Transactional
    public void recordRequests(ClaimedFiscalBatch batch, String requestXml) {
        var context = lock(batch);
        var evidenceId = persistRequestEvidence(batch, context, requestXml);
        for (var item : context.items()) {
            attempts.recordRequest(item.record().getId(), requestXml,
                    item.state().getClaimToken(), evidenceId);
        }
    }

    @Transactional
    public void recordTransportFailure(
            ClaimedFiscalBatch batch, String errorCode, String error,
            String requestXml) {
        recordUnknownResponse(batch, errorCode, error, requestXml, null, null);
    }

    /**
     * Persists a sent request whose response cannot be trusted. The fiscal
     * result is unknown, so lines become ENVIADO/retryable, never DEFECTUOSO.
     * A valid AEAT wait is still applied to the scope before releasing it.
     */
    @Transactional
    public void recordUnknownResponse(
            ClaimedFiscalBatch batch, String errorCode, String error,
        String requestXml, String responsePayload, Integer waitSeconds) {
        var context = lock(batch);
        var evidenceId = requireRequestEvidence(batch);
        var responseEvidenceId = persistResponseEvidence(batch, responsePayload);
        if (responseEvidenceId != null) evidenceId = responseEvidenceId;
        for (var item : context.items()) {
            attempts.recordTransportFailure(item.record().getId(), errorCode, error,
                    requestXml, responsePayload, item.state().getClaimToken(), evidenceId);
        }
        var now = Instant.now(clock);
        if (waitSeconds != null && waitSeconds >= 0 && waitSeconds <= 9999) {
            context.scope().completed(context.owner(), now, waitSeconds);
        } else {
            context.scope().release(context.owner(), now);
        }
        flows.save(context.scope());
    }

    /** Releases a claim after a local infrastructure failure before the HTTP call. */
    @Transactional
    public void releaseBeforeNetwork(
            ClaimedFiscalBatch batch, String errorCode, String error) {
        var context = lock(batch);
        var now = Instant.now(clock);
        for (var item : context.items()) {
            var state = states.findForUpdate(item.record().getId()).orElseThrow();
            state.markRetryableFailureWithClaim(errorCode, error, now,
                    item.state().getClaimToken());
            states.save(state);
        }
        context.scope().release(context.owner(), now);
        flows.save(context.scope());
    }

    @Transactional
    public void recordInvalid(
            ClaimedFiscalBatch batch, String errorCode, String error, String payload) {
        var context = lock(batch);
        for (var item : context.items()) {
            attempts.recordDefective(item.record().getId(), errorCode, error, payload,
                    item.state().getClaimToken());
        }
        context.scope().release(context.owner(), Instant.now(clock));
        flows.save(context.scope());
    }

    @Transactional
    public void recordResponse(
            ClaimedFiscalBatch batch, VerifactuBatchResponse response) {
        // The response evidence, every line transition, pacing and the first
        // submission marker deliberately share this transaction. A marker
        // failure therefore rolls back the ACK and leaves the live claim to
        // lease expiry/reconciliation.
        if (response == null || response.lines() == null) {
            throw new IllegalArgumentException("Respuesta fiscal ausente");
        }
        var context = lock(batch);
        var evidenceId = requireRequestEvidence(batch);
        var responseEvidenceId = persistResponseEvidence(batch, response.payload());
        if (responseEvidenceId != null) evidenceId = responseEvidenceId;
        for (var item : context.items()) {
            var line = response.lines().get(item.record().getId());
            if (line == null) throw new IllegalStateException("Respuesta sin linea fiscal");
            switch (line.status()) {
                case ACEPTADO -> {
                    attempts.recordAccepted(item.record().getId(), response.payload(),
                            item.state().getClaimToken(), evidenceId);
                    corrections.accepted(item.record());
                    markFirstSubmission(item.record());
                }
                case ACEPTADO_CON_ERRORES -> {
                    attempts.recordAcceptedWithErrors(item.record().getId(), line.errorCode(),
                            line.error(), response.payload(), item.state().getClaimToken(), evidenceId);
                    corrections.accepted(item.record());
                    markFirstSubmission(item.record());
                }
                case RECHAZADO -> attempts.recordRejected(item.record().getId(), line.errorCode(),
                        line.error(), response.payload(), item.state().getClaimToken(), evidenceId);
                default -> throw new IllegalStateException("Estado de linea no aplicable");
            }
        }
        var sentAt = Instant.now(clock);
        context.scope().completed(context.owner(), sentAt, response.waitSeconds());
        flows.save(context.scope());
    }

    private UUID persistRequestEvidence(
            ClaimedFiscalBatch batch, Context context, String requestXml) {
        if (evidences == null) return null;
        var normalized = FiscalSubmissionEvidence.requiredPayload(
                requestXml, "requestXml", FiscalSubmissionEvidence.MAX_REQUEST_BYTES);
        var hash = FiscalSubmissionEvidence.sha256(normalized);
        var existing = evidences.findById(batch.batchId());
        if (existing.isPresent()) {
            var evidence = existing.get();
            if (!evidence.getCompanyId().equals(context.scope().getCompanyId())
                    || !evidence.getInstallationId().equals(context.scope().getInstallationId())
                    || evidence.getEnvironment() != context.scope().getEnvironment()
                    || !evidence.getBatchOwner().equals(context.owner())
                    || !evidence.getRequestSha256().equals(hash)
                    || !evidence.getRequestXml().equals(normalized)) {
                throw new IllegalStateException("La evidencia del lote no coincide con la solicitud");
            }
            return evidence.getId();
        }
        var now = Instant.now(clock);
        return evidences.save(new FiscalSubmissionEvidence(
                batch.batchId(), context.scope().getCompanyId(),
                context.scope().getInstallationId(), context.scope().getEnvironment(),
                context.owner(), now, now, normalized, hash)).getId();
    }

    private UUID persistResponseEvidence(ClaimedFiscalBatch batch, String responsePayload) {
        if (responsePayload == null || responseEvidences == null || evidences == null) {
            return evidences == null ? null : batch.batchId();
        }
        var normalized = FiscalSubmissionEvidence.optionalPayload(
                responsePayload, "responsePayload",
                FiscalSubmissionResponseEvidence.MAX_RESPONSE_BYTES);
        var hash = FiscalSubmissionEvidence.sha256(normalized);
        var existing = responseEvidences.findByEvidenceId(batch.batchId());
        if (existing.isPresent()) {
            var response = existing.get();
            if (!response.getResponseSha256().equals(hash)
                    || !response.getResponsePayload().equals(normalized)) {
                throw new IllegalStateException("La respuesta del lote no coincide con la evidencia");
            }
        } else {
            responseEvidences.save(new FiscalSubmissionResponseEvidence(
                    batch.batchId(), Instant.now(clock), normalized, hash));
        }
        return batch.batchId();
    }

    private UUID requireRequestEvidence(ClaimedFiscalBatch batch) {
        if (evidences == null) return null;
        return evidences.findById(batch.batchId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe evidencia de solicitud para el lote fiscal"))
                .getId();
    }

    private void markFirstSubmission(FiscalRecord record) {
        if (firstSubmissions != null) firstSubmissions.mark(record);
    }

    private Context lock(ClaimedFiscalBatch batch) {
        if (batch == null || batch.scope() == null || batch.submissions().isEmpty()) {
            throw new IllegalArgumentException("Lote fiscal invalido");
        }
        var requested = batch.scope();
        var scope = flows.findForUpdate(requested.getCompanyId(), requested.getInstallationId(),
                        requested.getEnvironment())
                .orElseThrow(() -> new IllegalStateException("Scope fiscal no encontrado"));
        var now = Instant.now(clock);
        var owner = requested.getLeaseOwner();
        if (!scope.isOwnedBy(owner, now)) {
            throw new IllegalStateException("El worker ya no posee el scope fiscal");
        }
        var items = new ArrayList<ClaimedFiscalSubmission>(batch.submissions().size());
        for (var item : batch.submissions()) {
            var state = states.findForUpdate(item.record().getId())
                    .orElseThrow(() -> new IllegalStateException("Estado fiscal no encontrado"));
            if (!state.isOwnedBy(item.state().getClaimToken(), now)
                    || !owner.equals(state.getLeaseOwner())) {
                throw new IllegalStateException("El worker ya no posee el registro fiscal");
            }
            items.add(new ClaimedFiscalSubmission(item.record(), state));
        }
        return new Context(scope, owner, items);
    }

    private record Context(
            FiscalSubmissionScopeFlow scope,
            UUID owner,
            List<ClaimedFiscalSubmission> items) {
    }
}

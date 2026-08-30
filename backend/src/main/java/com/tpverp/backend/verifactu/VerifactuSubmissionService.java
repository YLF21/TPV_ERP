package com.tpverp.backend.verifactu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class VerifactuSubmissionService {

    private static final Logger LOG = LoggerFactory.getLogger(VerifactuSubmissionService.class);

    private final VerifactuXmlService xml;
    private final VerifactuSoapEnvelopeService soap;
    private final VerifactuEndpointResolver endpoints;
    private final VerifactuSubmissionPropertiesFactory properties;
    private final VerifactuTransport transport;
    private final FiscalSubmissionAttemptService attempts;
    private final VerifactuResponseParser responses;
    private final VerifactuOfficialXsdValidator validator;
    private final VerifactuFirstSubmissionMarker firstSubmissions;
    private final FiscalCorrectionCompletionService corrections;
    private final FrozenFiscalIdentityResolver identities;
    private FiscalRecordArtifactRepository artifacts;
    private FiscalRuntimeProperties runtime;
    private FiscalSubmissionScopeFlowRepository scopeFlows;
    private VerifactuBatchPersistenceService batchPersistence;
    private FiscalAlarmRepository alarms;
    private Clock clock = Clock.systemUTC();

    public VerifactuSubmissionService(
            VerifactuXmlService xml,
            VerifactuSoapEnvelopeService soap,
            VerifactuEndpointResolver endpoints,
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuTransport transport,
            FiscalSubmissionAttemptService attempts,
            VerifactuResponseParser responses,
            VerifactuOfficialXsdValidator validator,
            VerifactuFirstSubmissionMarker firstSubmissions,
            FiscalCorrectionCompletionService corrections,
            FrozenFiscalIdentityResolver identities) {
        this.xml = xml;
        this.soap = soap;
        this.endpoints = endpoints;
        this.properties = properties;
        this.transport = transport;
        this.attempts = attempts;
        this.responses = responses;
        this.validator = validator;
        this.firstSubmissions = firstSubmissions;
        this.corrections = corrections;
        this.identities = identities;
    }

    @Autowired(required = false)
    void setFrozenArtifacts(FiscalRecordArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    @Autowired(required = false)
    void setFiscalRuntimeProperties(FiscalRuntimeProperties runtime) {
        this.runtime = runtime;
    }

    @Autowired(required = false)
    void setScopeFlows(FiscalSubmissionScopeFlowRepository scopeFlows) {
        this.scopeFlows = scopeFlows;
    }

    @Autowired(required = false)
    void setBatchPersistence(VerifactuBatchPersistenceService batchPersistence) {
        this.batchPersistence = batchPersistence;
    }

    @Autowired(required = false)
    void setFiscalAlarms(FiscalAlarmRepository alarms) {
        this.alarms = alarms;
    }

    @Autowired(required = false)
    void setClock(Clock clock) {
        if (clock != null) this.clock = clock;
    }

    /** Sends one already claimed scope batch in exactly one SOAP call. */
    public VerifactuBatchSubmissionResult submitBatch(ClaimedFiscalBatch batch) {
        if (batch == null || batch.submissions().isEmpty()
                || batch.submissions().size() > 1000) {
            throw new IllegalArgumentException("Lote fiscal invalido");
        }
        var claimed = batch.submissions();
        if (claimed.stream().anyMatch(item -> item == null || item.record() == null
                || item.state() == null || item.record().getId() == null)) {
            throw new IllegalArgumentException("Lote fiscal invalido");
        }
        var records = claimed.stream().map(ClaimedFiscalSubmission::record).toList();
        var artifactsByRecord = new ArrayList<FrozenSubmissionArtifact>(records.size());
        boolean networkRequestIssued = false;
        boolean responsePersistenceAttempted = false;
        VerifactuBatchResponse parsed = null;
        String envelope = null;
        String rawResponsePayload = null;
        try {
            var frozenById = artifacts == null ? java.util.Map.<UUID, FiscalRecordArtifact>of()
                    : artifacts.findAllByRecordIdIn(records.stream()
                            .map(FiscalRecord::getId).toList()).stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    FiscalRecordArtifact::getRecordId, value -> value));
            for (var record : records) {
                var artifact = frozenById.get(record.getId());
                if (artifact == null) throw new IllegalStateException(
                        "No existe el artefacto fiscal congelado del registro");
                artifactsByRecord.add(frozenArtifact(record, artifact));
            }
            var first = artifactsByRecord.getFirst();
            for (int index = 0; index < artifactsByRecord.size(); index++) {
                var record = records.get(index);
                var artifact = artifactsByRecord.get(index);
                if (!batch.scope().getCompanyId().equals(record.getCompanyId())
                        || !batch.scope().getInstallationId().equals(record.getInstallationId())
                        || batch.scope().getEnvironment() != artifact.artifact().getEnvironment()) {
                    throw new IllegalArgumentException("El lote no pertenece al scope reclamado");
                }
            }
            for (var artifact : artifactsByRecord) {
                if (!first.issuerName().equals(artifact.issuerName())
                        || !first.issuerTaxId().equals(artifact.issuerTaxId())
                        || artifact.artifact().getEnvironment()
                                != first.artifact().getEnvironment()) {
                    throw new IllegalArgumentException("El lote mezcla identidades fiscales");
                }
            }
            var fiscalXml = xml.frozenBatchXml(
                    first.issuerName(), first.issuerTaxId(),
                    artifactsByRecord.stream()
                            .map(value -> value.artifact().getUnsignedXml()).toList());
            validator.validate(fiscalXml);
            envelope = soap.wrap(fiscalXml);
            if (batchPersistence != null) {
                batchPersistence.recordRequests(batch, envelope);
            } else {
                for (var item : claimed) {
                    attempts.recordRequest(item.record().getId(), envelope,
                            item.state().getClaimToken());
                }
            }
            final VerifactuTransportResponse response;
            try {
                response = transport.send(
                        records.getFirst().getCompanyId(), records.getFirst().getInstallationId(),
                        endpoints.resolve(endpointMode(first.artifact().getEnvironment())), envelope);
                networkRequestIssued = true;
                rawResponsePayload = response == null ? null : response.body();
            } catch (VerifactuTransportException exception) {
                if (batchPersistence != null) batchPersistence.recordTransportFailure(
                        batch, "NETWORK_ERROR", exceptionMessage(exception), envelope);
                else releaseScope(batch);
                var results = new ArrayList<VerifactuSubmissionResult>(claimed.size());
                for (var item : claimed) {
                    results.add(new VerifactuSubmissionResult(FiscalSubmissionStatus.ENVIADO,
                            "NETWORK_ERROR", exceptionMessage(exception), null, true));
                }
                return new VerifactuBatchSubmissionResult(true, FiscalSubmissionStatus.ENVIADO,
                        results, null, true, "NETWORK_ERROR", exceptionMessage(exception));
            }
            parsed = responses.parseBatch(response, records);
            if (parsed.transportFailure()) {
                if (batchPersistence != null) batchPersistence.recordUnknownResponse(
                        batch, parsed.errorCode(), parsed.error(), envelope,
                        parsed.payload(), null);
                else releaseScope(batch);
                return transportFailureAfterResponse(batch, envelope, parsed);
            }
            if (!parsed.validFor(records)) {
                if (batchPersistence != null) batchPersistence.recordUnknownResponse(
                        batch, "INVALID_AEAT_RESPONSE", parsed.error(), envelope,
                        parsed.payload(), parsed.waitSeconds());
                else releaseScope(batch);
                var results = new ArrayList<VerifactuSubmissionResult>(claimed.size());
                for (var item : claimed) {
                    var result = new VerifactuSubmissionResult(FiscalSubmissionStatus.ENVIADO,
                            "INVALID_AEAT_RESPONSE", parsed.error(), parsed.payload(), true);
                    results.add(result);
                }
                return new VerifactuBatchSubmissionResult(true, FiscalSubmissionStatus.ENVIADO,
                        results, parsed.waitSeconds(), true, "INVALID_AEAT_RESPONSE", parsed.error());
            }
            var results = new ArrayList<VerifactuSubmissionResult>(claimed.size());
            for (var item : claimed) {
                var line = parsed.lines().get(item.record().getId());
                var result = new VerifactuSubmissionResult(line.status(), line.errorCode(),
                        line.error(), parsed.payload(), true);
                results.add(result);
            }
            if (batchPersistence != null) {
                responsePersistenceAttempted = true;
                batchPersistence.recordResponse(batch, parsed);
            } else {
                completeScope(batch, parsed.waitSeconds());
                for (int index = 0; index < claimed.size(); index++) {
                    var item = claimed.get(index);
                    recordBatchResult(item.record(), results.get(index), item.state().getClaimToken());
                }
            }
            return new VerifactuBatchSubmissionResult(true, parsed.globalStatus(), results,
                    parsed.waitSeconds(), true, parsed.errorCode(), parsed.error());
        } catch (RuntimeException exception) {
            if (networkRequestIssued && !responsePersistenceAttempted) {
                try {
                    if (batchPersistence != null) {
                        batchPersistence.recordUnknownResponse(batch,
                                "INVALID_AEAT_RESPONSE", exceptionMessage(exception),
                                envelope, rawResponsePayload,
                                parsed == null ? null : parsed.waitSeconds());
                    } else {
                        releaseScope(batch);
                    }
                } catch (IllegalStateException ownershipLost) {
                    return new VerifactuBatchSubmissionResult(false,
                            FiscalSubmissionStatus.ENVIADO, List.of(), null, true,
                            "STALE_CLAIM", ownershipLost.getMessage());
                }
                var results = new ArrayList<VerifactuSubmissionResult>(claimed.size());
                for (var item : claimed) {
                    results.add(new VerifactuSubmissionResult(FiscalSubmissionStatus.ENVIADO,
                            "INVALID_AEAT_RESPONSE", exceptionMessage(exception),
                            parsed == null ? null : parsed.payload(), true));
                }
                return new VerifactuBatchSubmissionResult(true,
                        FiscalSubmissionStatus.ENVIADO, results,
                        parsed == null ? null : parsed.waitSeconds(), true,
                         "INVALID_AEAT_RESPONSE", exceptionMessage(exception));
            }
            if (networkRequestIssued && responsePersistenceAttempted) {
                // The AEAT request is already on the wire. Losing the ACK
                // transaction must never turn a submitted record into a local
                // DEFECTUOSO/retry state or release its live lease.
                return new VerifactuBatchSubmissionResult(false,
                        parsed == null ? null : parsed.globalStatus(), List.of(),
                        parsed == null ? null : parsed.waitSeconds(), true,
                        "ACK_PERSISTENCE_FAILED", exceptionMessage(exception));
            }
            if (!isDeterministicLocalFailure(exception)) {
                try {
                    if (batchPersistence != null) {
                        batchPersistence.releaseBeforeNetwork(batch,
                                "PRE_NETWORK_INFRASTRUCTURE_FAILED", exceptionMessage(exception));
                    } else {
                        releaseScope(batch);
                    }
                } catch (RuntimeException releaseFailure) {
                    LOG.error("No se pudo liberar el claim fiscal tras un fallo previo a red", releaseFailure);
                }
                return new VerifactuBatchSubmissionResult(false,
                        FiscalSubmissionStatus.ENVIADO, List.of(), null, false,
                         "PRE_NETWORK_INFRASTRUCTURE_FAILED", exceptionMessage(exception));
            }
            var defectCode = exceptionMessage(exception).toUpperCase(java.util.Locale.ROOT).contains("XSD")
                            ? "INVALID_XSD" : "INVALID_AEAT_RESPONSE";
            if (batchPersistence != null) {
                try {
                    batchPersistence.recordInvalid(batch, defectCode, exceptionMessage(exception), null);
                } catch (IllegalStateException ownershipLost) {
                    // A stale worker must not mutate a newer claim.
                    return new VerifactuBatchSubmissionResult(false,
                            FiscalSubmissionStatus.DEFECTUOSO, List.of(), null,
                            false, "STALE_CLAIM", ownershipLost.getMessage());
                }
            } else releaseScope(batch);
            var results = new ArrayList<VerifactuSubmissionResult>(claimed.size());
            for (var item : claimed) {
                var result = new VerifactuSubmissionResult(FiscalSubmissionStatus.DEFECTUOSO,
                        defectCode, exceptionMessage(exception), null, false);
                results.add(result);
            }
            return new VerifactuBatchSubmissionResult(true, FiscalSubmissionStatus.DEFECTUOSO,
                    results, null, false, defectCode, exceptionMessage(exception));
        }
    }

    private static boolean isDeterministicLocalFailure(RuntimeException exception) {
        var message = exceptionMessage(exception).toLowerCase(java.util.Locale.ROOT);
        return exception instanceof IllegalArgumentException
                || message.contains("xsd") || message.contains("xml verifactu")
                || message.contains("artefacto fiscal") || message.contains("huella persistida")
                || message.contains("identidad fiscal") || message.contains("entorno actual")
                || message.contains("lote fiscal invalido");
    }

    private static String exceptionMessage(Throwable exception) {
        if (exception == null) return "Error fiscal no especificado";
        var message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.trim();
    }

    private VerifactuBatchSubmissionResult transportFailureAfterResponse(
            ClaimedFiscalBatch batch, String envelope, VerifactuBatchResponse parsed) {
        var results = new ArrayList<VerifactuSubmissionResult>();
        for (var item : batch.submissions()) {
            results.add(new VerifactuSubmissionResult(FiscalSubmissionStatus.ENVIADO,
                    parsed.errorCode(), parsed.error(), null, true));
        }
        return new VerifactuBatchSubmissionResult(true, FiscalSubmissionStatus.ENVIADO,
                results, null, true, parsed.errorCode(), parsed.error());
    }

    private void markBatchFirstSubmissions(
            List<ClaimedFiscalSubmission> claimed,
            List<VerifactuSubmissionResult> results,
            ClaimedFiscalBatch batch) {
        if (firstSubmissions == null) return;
        for (int index = 0; index < claimed.size(); index++) {
            var status = results.get(index).status();
            if (status != FiscalSubmissionStatus.ACEPTADO
                    && status != FiscalSubmissionStatus.ACEPTADO_CON_ERRORES) continue;
            try {
                firstSubmissions.mark(claimed.get(index).record());
            } catch (RuntimeException exception) {
                LOG.error("No se pudo actualizar el marker de primera remision para {}",
                        claimed.get(index).record().getId(), exception);
                recordFirstSubmissionMarkerFailure(claimed.get(index).record(), batch, exception);
            }
        }
    }

    private void recordFirstSubmissionMarkerFailure(
            FiscalRecord record, ClaimedFiscalBatch batch, RuntimeException exception) {
        if (alarms == null) return;
        try {
            alarms.save(new FiscalAlarm(record.getCompanyId(), record.getInstallationId(),
                    "FIRST_SUBMISSION_MARKER_FAILED",
                    "scope=" + batch.scope().getCompanyId() + "/" + batch.scope().getInstallationId()
                            + "/" + batch.scope().getEnvironment() + "; record=" + record.getId()
                            + "; cause=" + exceptionMessage(exception), clock.instant()));
        } catch (RuntimeException alarmFailure) {
            LOG.error("No se pudo guardar la alarma de fallo del marker para {}",
                    record.getId(), alarmFailure);
        }
    }

    private void recordBatchResult(FiscalRecord record, VerifactuSubmissionResult result,
            UUID token) {
        switch (result.status()) {
            case ACEPTADO -> {
                attempts.recordAccepted(record.getId(), result.responsePayload(), token);
                markFirstSubmission(record);
                corrections.accepted(record);
            }
            case ACEPTADO_CON_ERRORES -> {
                attempts.recordAcceptedWithErrors(record.getId(), result.errorCode(), result.error(),
                        result.responsePayload(), token);
                markFirstSubmission(record);
                corrections.accepted(record);
            }
            case RECHAZADO -> attempts.recordRejected(record.getId(), result.errorCode(),
                    result.error(), result.responsePayload(), token);
            default -> attempts.recordDefective(record.getId(), "INVALID_AEAT_RESPONSE",
                    "Estado de linea no aplicable", result.responsePayload(), token);
        }
    }

    private void completeScope(ClaimedFiscalBatch batch, int waitSeconds) {
        batch.scope().completed(java.time.Instant.now(clock), waitSeconds);
        if (scopeFlows != null) scopeFlows.save(batch.scope());
    }

    private void releaseScope(ClaimedFiscalBatch batch) {
        batch.scope().release();
        if (scopeFlows != null) scopeFlows.save(batch.scope());
    }

    /** Package-private compatibility path; production dispatch goes through a claimed batch. */
    VerifactuSubmissionResult submit(FiscalRecord record) {
        return submit(record, null);
    }

    /** Submits only while the supplied durable claim token is still owned. */
    VerifactuSubmissionResult submit(FiscalRecord record, java.util.UUID claimToken) {
        if (record == null || record.getFiscalMode() != FiscalMode.VERIFACTU) {
            throw new IllegalArgumentException(
                    "Solo se pueden enviar registros fiscales VERI*FACTU");
        }
        final FrozenSubmissionArtifact frozen;
        try {
            frozen = frozenArtifact(record);
        } catch (UnresolvedLegacyFiscalIdentityException exception) {
            var result = new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.DEFECTUOSO,
                    "LEGACY_IDENTITY_UNRESOLVED",
                    exceptionMessage(exception),
                    null,
                    false);
            recordDefective(record, result, null, claimToken);
            return result;
        }
        var artifact = frozen.artifact();
        var fiscalXml = xml.frozenBatchXml(
                frozen.issuerName(), frozen.issuerTaxId(),
                java.util.List.of(artifact.getUnsignedXml()));
        try {
            validator.validate(fiscalXml);
        } catch (IllegalArgumentException exception) {
            var result = new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.DEFECTUOSO,
                    "INVALID_XSD",
                    exceptionMessage(exception),
                    fiscalXml,
                    false);
            recordDefective(record, result, fiscalXml, claimToken);
            return result;
        }
        var envelope = soap.wrap(fiscalXml);
        if (claimToken == null) {
            attempts.recordSent(record.getId(), envelope);
        } else {
            attempts.recordRequest(record.getId(), envelope, claimToken);
        }
        try {
            var response = transport.send(
                    record.getCompanyId(), record.getInstallationId(),
                    endpoints.resolve(endpointMode(artifact.getEnvironment())), envelope);
            if (response == null) {
                throw new VerifactuTransportException("Transporte VERI*FACTU devolvio respuesta nula");
            }
            var parsed = responses.parse(response);
            var networkResult = new VerifactuSubmissionResult(
                    parsed.status(), parsed.errorCode(), parsed.error(),
                    parsed.responsePayload(), true);
            return recordResult(record, networkResult, claimToken, envelope);
        } catch (VerifactuTransportException exception) {
            if (claimToken != null) {
                attempts.recordTransportFailure(
                        record.getId(), "NETWORK_ERROR", exceptionMessage(exception), envelope, claimToken);
            }
            return new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.ENVIADO,
                    "NETWORK_ERROR",
                    exceptionMessage(exception),
                    null,
                    true);
        }
    }
    // Envia un registro fiscal ya reclamado y aplica la politica de estado sin bloquear ventas.

    private FrozenSubmissionArtifact frozenArtifact(FiscalRecord record) {
        if (artifacts == null || runtime == null) {
            throw new IllegalStateException(
                    "El envio VERI*FACTU requiere artefacto y entorno fiscal congelados");
        }
        var artifact = artifacts.findByRecordId(record.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el artefacto fiscal congelado del registro"));
        return frozenArtifact(record, artifact);
    }

    private FrozenSubmissionArtifact frozenArtifact(
            FiscalRecord record, FiscalRecordArtifact artifact) {
        if (artifacts == null || runtime == null) {
            throw new IllegalStateException(
                    "El envio VERI*FACTU requiere artefacto y entorno fiscal congelados");
        }
        if (artifact.getFiscalMode() != FiscalMode.VERIFACTU
                || artifact.getEnvironment() == null
                || artifact.getUnsignedXml() == null) {
            throw new IllegalStateException(
                    "El artefacto VERI*FACTU no contiene XML y entorno congelados");
        }
        if (runtime.endpointEnvironment() != artifact.getEnvironment()) {
            throw new IllegalStateException(
                    "El entorno actual no coincide con el entorno congelado del artefacto");
        }
        var calculatedHash = sha256(artifact.getUnsignedXml());
        if (!calculatedHash.equalsIgnoreCase(String.valueOf(artifact.getXmlHash()))) {
            throw new IllegalStateException(
                    "El XML congelado no coincide con su huella persistida");
        }
        var identity = identities.resolve(record, artifact);
        return new FrozenSubmissionArtifact(
                artifact, identity.issuerName(), identity.issuerTaxId());
    }

    private static VerifactuEndpointMode endpointMode(FiscalEndpointEnvironment environment) {
        return switch (environment) {
            case TEST -> VerifactuEndpointMode.TEST;
            case PRODUCTION -> VerifactuEndpointMode.PRODUCTION;
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private record FrozenSubmissionArtifact(
            FiscalRecordArtifact artifact,
            String issuerName,
            String issuerTaxId) {
    }

    private VerifactuSubmissionResult recordResult(
            FiscalRecord record, VerifactuSubmissionResult result,
            java.util.UUID claimToken, String requestXml) {
        switch (result.status()) {
            case ACEPTADO -> {
                if (claimToken == null) {
                    attempts.recordAccepted(record.getId(), result.responsePayload());
                } else {
                    attempts.recordAccepted(record.getId(), result.responsePayload(), claimToken);
                }
                markFirstSubmission(record);
                corrections.accepted(record);
            }
            case ACEPTADO_CON_ERRORES -> {
                if (claimToken == null) {
                    attempts.recordAcceptedWithErrors(
                            record.getId(), result.errorCode(), result.error(), result.responsePayload());
                } else {
                    attempts.recordAcceptedWithErrors(
                            record.getId(), result.errorCode(), result.error(), result.responsePayload(),
                            claimToken);
                }
                markFirstSubmission(record);
                corrections.accepted(record);
            }
            case RECHAZADO -> {
                if (claimToken == null) {
                    attempts.recordRejected(record.getId(), result.errorCode(), result.error(), result.responsePayload());
                } else {
                    attempts.recordRejected(record.getId(), result.errorCode(), result.error(), result.responsePayload(), claimToken);
                }
            }
            case DEFECTUOSO -> {
                if (claimToken == null) {
                    attempts.recordDefective(record.getId(), result.errorCode(), result.error(), result.responsePayload());
                } else {
                    attempts.recordDefective(record.getId(), result.errorCode(), result.error(), result.responsePayload(), claimToken);
                }
            }
            case ENVIADO -> {
                if (claimToken != null) {
                    attempts.recordTransportFailure(record.getId(), result.errorCode(), result.error(), requestXml, claimToken);
                }
            }
            default -> { }
        }
        return result;
    }

    private void recordDefective(
            FiscalRecord record,
            VerifactuSubmissionResult result,
            String responsePayload,
            java.util.UUID claimToken) {
        if (claimToken == null) {
            attempts.recordDefective(
                    record.getId(), result.errorCode(), result.error(), responsePayload);
        } else {
            attempts.recordDefective(
                    record.getId(), result.errorCode(), result.error(), responsePayload, claimToken);
        }
    }

    private void markFirstSubmission(FiscalRecord record) {
        if (firstSubmissions != null) {
            try {
                firstSubmissions.mark(record);
            } catch (RuntimeException exception) {
                // The ACK/state and attempt are already durable. Never turn a
                // post-ACK marker failure into a false defective/retry state.
                LOG.error("No se pudo actualizar el marker de primera remision para {}",
                        record.getId(), exception);
                if (alarms != null) {
                    try {
                        alarms.save(new FiscalAlarm(record.getCompanyId(), record.getInstallationId(),
                                "FIRST_SUBMISSION_MARKER_FAILED",
                                 "record=" + record.getId() + "; cause="
                                         + exceptionMessage(exception), clock.instant()));
                    } catch (RuntimeException alarmFailure) {
                        LOG.error("No se pudo guardar la alarma de fallo del marker para {}",
                                record.getId(), alarmFailure);
                    }
                }
            }
        }
    }
}

package com.tpverp.backend.verifactu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VerifactuSubmissionService {

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

    public VerifactuSubmissionResult submit(FiscalRecord record) {
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
                    exception.getMessage(),
                    null);
            attempts.recordDefective(
                    record.getId(), result.errorCode(), result.error(), null);
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
                    exception.getMessage(),
                    fiscalXml);
            attempts.recordDefective(record.getId(), result.errorCode(), result.error(), fiscalXml);
            return result;
        }
        var envelope = soap.wrap(fiscalXml);
        attempts.recordSent(record.getId(), envelope);
        try {
            var response = transport.send(
                    record.getCompanyId(), record.getInstallationId(),
                    endpoints.resolve(endpointMode(artifact.getEnvironment())), envelope);
            // Keeps older transport test doubles source-compatible while the real
            // implementation always receives the explicit fiscal identity above.
            if (response == null) {
                response = transport.send(
                        endpoints.resolve(endpointMode(artifact.getEnvironment())), envelope);
            }
            return recordResult(record, responses.parse(response));
        } catch (VerifactuTransportException exception) {
            return new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.ENVIADO,
                    "NETWORK_ERROR",
                    exception.getMessage(),
                    null);
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
            FiscalRecord record, VerifactuSubmissionResult result) {
        switch (result.status()) {
            case ACEPTADO -> {
                markFirstSubmission(record);
                attempts.recordAccepted(record.getId(), result.responsePayload());
                corrections.accepted(record);
            }
            case ACEPTADO_CON_ERRORES -> {
                markFirstSubmission(record);
                attempts.recordAcceptedWithErrors(
                        record.getId(), result.errorCode(), result.error(), result.responsePayload());
            }
            case RECHAZADO -> attempts.recordRejected(
                    record.getId(), result.errorCode(), result.error(), result.responsePayload());
            case DEFECTUOSO -> attempts.recordDefective(
                    record.getId(), result.errorCode(), result.error(), result.responsePayload());
            default -> { }
        }
        return result;
    }

    private void markFirstSubmission(FiscalRecord record) {
        if (firstSubmissions != null) {
            firstSubmissions.mark(record);
        }
    }
}

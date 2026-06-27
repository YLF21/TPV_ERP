package com.tpverp.backend.verifactu;

import java.util.List;
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
            FiscalCorrectionCompletionService corrections) {
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
    }

    public VerifactuSubmissionResult submit(FiscalRecord record) {
        var configuration = properties.current();
        var fiscalXml = fiscalXml(record, configuration);
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
            var response = transport.send(endpoints.resolve(configuration.mode()), envelope);
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

    private String fiscalXml(FiscalRecord record, VerifactuSubmissionProperties configuration) {
        var system = new VerifactuSystemInfo(
                "TPV ERP", record.getIssuerTaxId(), configuration.systemName(),
                configuration.systemId(), "0.0.1", record.getStoreId().toString(),
                true, false, false);
        return xml.batchXml(new VerifactuXmlBatchRequest(
                "Company", record.getIssuerTaxId(), List.of(record), system));
    }

    private VerifactuSubmissionResult recordResult(
            FiscalRecord record, VerifactuSubmissionResult result) {
        switch (result.status()) {
            case ACEPTADO -> {
                attempts.recordAccepted(record.getId(), result.responsePayload());
                corrections.accepted(record);
                markFirstSubmission(record);
            }
            case ACEPTADO_CON_ERRORES -> {
                attempts.recordAcceptedWithErrors(
                        record.getId(), result.errorCode(), result.error(), result.responsePayload());
                markFirstSubmission(record);
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

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

    public VerifactuSubmissionService(
            VerifactuXmlService xml,
            VerifactuSoapEnvelopeService soap,
            VerifactuEndpointResolver endpoints,
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuTransport transport,
            FiscalSubmissionAttemptService attempts) {
        this(xml, soap, endpoints, properties, transport, attempts, new VerifactuResponseParser());
    }

    VerifactuSubmissionService(
            VerifactuXmlService xml,
            VerifactuSoapEnvelopeService soap,
            VerifactuEndpointResolver endpoints,
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuTransport transport,
            FiscalSubmissionAttemptService attempts,
            VerifactuResponseParser responses) {
        this.xml = xml;
        this.soap = soap;
        this.endpoints = endpoints;
        this.properties = properties;
        this.transport = transport;
        this.attempts = attempts;
        this.responses = responses;
    }

    public VerifactuSubmissionResult submit(FiscalRecord record) {
        var configuration = properties.current();
        var envelope = envelope(record, configuration);
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

    private String envelope(FiscalRecord record, VerifactuSubmissionProperties configuration) {
        var system = new VerifactuSystemInfo(
                "TPV ERP", record.getIssuerTaxId(), configuration.systemName(),
                configuration.systemId(), "0.0.1", record.getStoreId().toString(),
                true, false, false);
        return soap.wrap(xml.batchXml(new VerifactuXmlBatchRequest(
                "Empresa", record.getIssuerTaxId(), List.of(record), system)));
    }

    private VerifactuSubmissionResult recordResult(
            FiscalRecord record, VerifactuSubmissionResult result) {
        switch (result.status()) {
            case ACEPTADO -> attempts.recordAccepted(record.getId(), result.responsePayload());
            case ACEPTADO_CON_ERRORES -> attempts.recordAcceptedWithErrors(
                    record.getId(), result.errorCode(), result.error(), result.responsePayload());
            case RECHAZADO -> attempts.recordRejected(
                    record.getId(), result.errorCode(), result.error(), result.responsePayload());
            case DEFECTUOSO -> attempts.recordDefective(
                    record.getId(), result.errorCode(), result.error(), result.responsePayload());
            default -> { }
        }
        return result;
    }
}

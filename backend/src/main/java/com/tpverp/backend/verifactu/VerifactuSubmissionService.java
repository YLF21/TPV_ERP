package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CompanyRepository;
import java.util.List;
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
    private CompanyRepository companies;
    private InstallationRepository installations;
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

    /** Fiscal XML must resolve identity from the persisted company and installation. */
    @Autowired(required = false)
    void setFiscalIdentityRepositories(CompanyRepository companies,
            InstallationRepository installations) {
        this.companies = companies;
        this.installations = installations;
    }

    @Autowired(required = false)
    void setFiscalRuntimeProperties(FiscalRuntimeProperties runtime) {
        this.runtime = runtime;
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
            var response = transport.send(
                    record.getCompanyId(), record.getInstallationId(),
                    endpoints.resolve(configuration.mode()), envelope);
            // Keeps older transport test doubles source-compatible while the real
            // implementation always receives the explicit fiscal identity above.
            if (response == null) {
                response = transport.send(endpoints.resolve(configuration.mode()), envelope);
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

    private String fiscalXml(FiscalRecord record, VerifactuSubmissionProperties configuration) {
        if (companies == null || installations == null) {
            if (runtime != null && !runtime.isSandbox()) {
                throw new IllegalStateException(
                        "La identidad fiscal persistida es obligatoria fuera de SANDBOX");
            }
            return xml.batchXml(new VerifactuXmlBatchRequest(
                    "Company", record.getIssuerTaxId(), List.of(record),
                    system(configuration, record.getInstallationId().toString())));
        }
        var company = companies.findById(record.getCompanyId())
                .orElseThrow(() -> new IllegalStateException("Empresa fiscal no encontrada"));
        var installation = installations.findById(record.getInstallationId())
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        return xml.batchXml(new VerifactuXmlBatchRequest(
                company.getRazonSocial(), record.getIssuerTaxId(), List.of(record),
                system(configuration, installation.getReferencia())));
    }

    private VerifactuSystemInfo system(
            VerifactuSubmissionProperties configuration, String installationNumber) {
        var system = new VerifactuSystemInfo(
                configuration.producerName(), configuration.producerTaxId(),
                configuration.systemName(), configuration.systemId(),
                configuration.systemVersion(),
                installationNumber,
                true, false, false);
        return system;
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

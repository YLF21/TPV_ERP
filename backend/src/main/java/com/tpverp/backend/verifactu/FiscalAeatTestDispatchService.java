package com.tpverp.backend.verifactu;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class FiscalAeatTestDispatchService {

    private static final String CONFIRMATION = "CONFIRMAR_AEAT_TEST";
    private final FiscalRuntimeProperties runtime;
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final VerifactuSubmissionWorker worker;
    private final AuditService audit;
    private final FiscalResponsibleDeclarationService declarations;

    public FiscalAeatTestDispatchService(
            FiscalRuntimeProperties runtime,
            CurrentOrganization organization,
            InstallationRepository installations,
            LicenseRepository licenses,
            VerifactuSubmissionWorker worker,
            AuditService audit,
            FiscalResponsibleDeclarationService declarations) {
        this.runtime = runtime;
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.worker = worker;
        this.audit = audit;
        this.declarations = declarations;
    }

    public FiscalAeatTestDispatchView dispatch(
            FiscalAeatTestDispatchRequest request,
            Authentication authentication) {
        requireRequest(request);
        var details = baseDetails(request, authentication);
        try {
            validateRuntime(request);
            validateResponsibleDeclaration();
            validateScope(request);
            var result = worker.processPendingForScope(
                    request.companyId(), request.installationId(), request.recordId());
            var recordId = result.recordId() == null ? request.recordId() : result.recordId();
            var response = view(request, recordId, result);
            details.put("recordId", recordId == null ? null : recordId.toString());
            details.put("resultStatus", result.status() == null ? null : result.status().name());
            details.put("processed", result.processed());
            if (result.errorCode() != null) {
                details.put("errorCode", result.errorCode());
            }
            audit.record("VERIFACTU_AEAT_TEST_DISPATCH", auditResult(result), details);
            return response;
        } catch (RuntimeException exception) {
            details.put("resultStatus", "REJECTED");
            details.put("cause", exception.getClass().getSimpleName());
            audit.record("VERIFACTU_AEAT_TEST_DISPATCH", AuditResult.FALLO, details);
            throw exception;
        }
    }

    public FiscalAeatTestDispatchView dispatch(FiscalAeatTestDispatchRequest request) {
        return dispatch(request, SecurityContextHolder.getContext().getAuthentication());
    }

    private void validateRuntime(FiscalAeatTestDispatchRequest request) {
        if (runtime.runtimeClass() != FiscalRuntimeClass.SANDBOX) {
            throw new IllegalStateException("AEAT TEST manual requiere runtime SANDBOX");
        }
        if (runtime.endpointEnvironment() != FiscalEndpointEnvironment.TEST) {
            throw new IllegalStateException("AEAT TEST manual requiere endpoint TEST");
        }
        if (runtime.transportMode() != FiscalTransportMode.AEAT) {
            throw new IllegalStateException("AEAT TEST manual requiere transporte AEAT");
        }
        runtime.requireAeatTestNetwork();
        var manifest = runtime.releaseManifest();
        if (manifest == null) {
            throw new IllegalStateException("No existe un manifiesto de release activo");
        }
        runtime.requireAeatTestReleaseCandidate();
        var releaseId = manifest.releaseId();
        if (!releaseId.equals(request.expectedReleaseId().trim())) {
            throw new IllegalStateException("expectedReleaseId no coincide con el release activo");
        }
        if (!CONFIRMATION.equals(request.confirmation())) {
            throw new IllegalArgumentException(
                    "La confirmacion debe ser exactamente CONFIRMAR_AEAT_TEST");
        }
    }

    private void validateScope(FiscalAeatTestDispatchRequest request) {
        var currentCompany = organization.currentCompany();
        if (currentCompany == null || !request.companyId().equals(currentCompany.getId())) {
            throw new NoSuchElementException("La empresa solicitada no pertenece al contexto actual");
        }
        var resolved = FiscalInstallationResolver.resolveForCompany(
                request.companyId(), installations, licenses);
        if (!request.installationId().equals(resolved.getId())) {
            throw new NoSuchElementException(
                    "La instalacion solicitada no pertenece al contexto fiscal actual");
        }
    }

    private void validateResponsibleDeclaration() {
        var manifest = runtime.releaseManifest();
        if (declarations == null) {
            throw new IllegalStateException(
                    "La declaracion responsable PDF no esta disponible");
        }
        var declaration = declarations.status();
        if (declaration == null || !"AVAILABLE".equals(declaration.status())
                || !manifest.releaseId().equals(declaration.releaseId())
                || !"application/pdf".equalsIgnoreCase(declaration.contentType())
                || declaration.size() == null || declaration.size() < 1
                || declaration.sha256() == null
                || !declaration.sha256().matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "La declaracion responsable PDF no esta disponible o no es valida");
        }
    }

    private FiscalAeatTestDispatchView view(
            FiscalAeatTestDispatchRequest request,
            UUID recordId,
            VerifactuWorkerResult result) {
        var evidence = new FiscalAeatTestDispatchView.EvidenceMetadata(
                runtime.releaseManifest().releaseId(),
                runtime.runtimeClass(),
                runtime.endpointEnvironment(),
                runtime.transportMode(),
                request.companyId(),
                request.installationId(),
                recordId,
                result.networkRequestIssued(),
                true);
        return new FiscalAeatTestDispatchView(
                result.processed(), result.status(), result.errorCode(),
                sanitizeError(result.error(), result.errorCode()), evidence);
    }

    private Map<String, Object> baseDetails(
            FiscalAeatTestDispatchRequest request,
            Authentication authentication) {
        var details = new LinkedHashMap<String, Object>();
        details.put("actor", authentication == null ? null : authentication.getName());
        details.put("companyId", request.companyId() == null ? null : request.companyId().toString());
        details.put("installationId",
                request.installationId() == null ? null : request.installationId().toString());
        details.put("recordId", request.recordId() == null ? null : request.recordId().toString());
        details.put("releaseId", runtime.releaseManifest() == null
                ? null : runtime.releaseManifest().releaseId());
        details.put("runtimeClass", runtime.runtimeClass() == null
                ? null : runtime.runtimeClass().name());
        details.put("endpointEnvironment", runtime.endpointEnvironment() == null
                ? null : runtime.endpointEnvironment().name());
        details.put("transport", runtime.transportMode() == null
                ? null : runtime.transportMode().name());
        return details;
    }

    private static AuditResult auditResult(VerifactuWorkerResult result) {
        return result.status() == FiscalSubmissionStatus.ACEPTADO
                || result.status() == FiscalSubmissionStatus.ACEPTADO_CON_ERRORES
                ? AuditResult.EXITO : AuditResult.FALLO;
    }

    private static String sanitizeError(String error, String errorCode) {
        if (error == null || error.isBlank()) {
            return null;
        }
        var normalized = error.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.contains("<") || normalized.contains(">")
                || normalized.matches(".*(?i)(password|passwd|secret|private[ _-]?key|token|certificate|certificado).*")) {
            return "El envio AEAT TEST devolvio un resultado con error (" + errorCode + ")";
        }
        if (normalized.length() > 256) {
            return normalized.substring(0, 256) + "...";
        }
        return normalized;
    }

    private static void requireRequest(FiscalAeatTestDispatchRequest request) {
        Objects.requireNonNull(request, "La solicitud AEAT TEST es obligatoria");
        if (request.companyId() == null || request.installationId() == null) {
            throw new IllegalArgumentException("companyId e installationId son obligatorios");
        }
        if (request.expectedReleaseId() == null || request.expectedReleaseId().isBlank()) {
            throw new IllegalArgumentException("expectedReleaseId es obligatorio");
        }
    }
}

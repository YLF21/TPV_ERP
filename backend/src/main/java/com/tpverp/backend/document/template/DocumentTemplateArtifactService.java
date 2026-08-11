package com.tpverp.backend.document.template;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentTemplateArtifactService {

    private final DocumentTemplateRepository templates;
    private final CurrentOrganization organization;
    private final SafeJrxmlCompiler compiler;
    private final DocumentTemplateArtifactStorage storage;
    private final DocumentTemplateCatalogService catalog;
    private final AuditService audit;
    private final Clock clock;

    public DocumentTemplateArtifactService(
            DocumentTemplateRepository templates,
            CurrentOrganization organization,
            SafeJrxmlCompiler compiler,
            DocumentTemplateArtifactStorage storage,
            DocumentTemplateCatalogService catalog,
            AuditService audit,
            Clock clock) {
        this.templates = templates;
        this.organization = organization;
        this.compiler = compiler;
        this.storage = storage;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DocumentTemplateCatalogService.TemplateView uploadAndValidate(
            UUID templateId, MultipartFile file) {
        Objects.requireNonNull(file, "file");
        var template = currentStoreTemplateForUpdate(templateId);
        if (template.getStatus() != DocumentTemplateStatus.DRAFT) {
            throw new IllegalStateException("document_template_not_draft");
        }
        var compiled = compiler.compile(read(file));
        DocumentTemplateArtifactStorage.StoredArtifact artifact;
        try {
            artifact = storage.write(
                    template.getId(), compiled.source(), compiled.compiled());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "document_template_artifact_storage_failed", exception);
        }
        deleteArtifactOnRollback(artifact.reference());
        template.validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                artifact.reference(),
                compiled.sha256(),
                clock.instant());
        var saved = templates.saveAndFlush(template);
        audit.record("DOCUMENT_TEMPLATE_VALIDATED", AuditResult.EXITO, auditDetails(saved));
        return DocumentTemplateCatalogService.TemplateView.from(saved);
    }

    @Transactional(readOnly = true)
    public SourceDownload source(UUID templateId) {
        var store = organization.currentStore();
        var template = templates.findStoreTemplate(
                        templateId, store.getEmpresa().getId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "document_template_not_found"));
        if (template.getArtifactReference() == null) {
            throw new IllegalStateException("document_template_artifact_not_available");
        }
        try {
            return new SourceDownload(
                    template.getCode().toLowerCase(java.util.Locale.ROOT)
                            + "_v" + template.getTemplateVersion() + ".jrxml",
                    storage.readSource(template.getArtifactReference()));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "document_template_artifact_read_failed", exception);
        }
    }

    public DocumentTemplateCatalogService.TemplateView activate(UUID templateId) {
        var store = organization.currentStore();
        var template = templates.findStoreTemplate(
                        templateId, store.getEmpresa().getId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "document_template_not_found"));
        if (template.getStatus() != DocumentTemplateStatus.VALIDATED
                && template.getStatus() != DocumentTemplateStatus.ACTIVE) {
            throw new IllegalStateException("document_template_not_validated");
        }
        verifyStoredArtifact(template);
        return catalog.activateValidatedCurrentStoreTemplate(templateId);
    }

    private DocumentTemplate currentStoreTemplateForUpdate(UUID templateId) {
        var store = organization.currentStore();
        return templates.findStoreTemplateForUpdate(
                        templateId, store.getEmpresa().getId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "document_template_not_found"));
    }

    private void verifyStoredArtifact(DocumentTemplate template) {
        try {
            byte[] source = storage.readSource(template.getArtifactReference());
            byte[] compiled = storage.readCompiled(template.getArtifactReference());
            if (compiled.length == 0
                    || !SafeJrxmlCompiler.sha256(source).equals(template.getSha256())) {
                throw new IllegalStateException("document_template_artifact_integrity_failed");
            }
            compiler.compile(source);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "document_template_artifact_read_failed", exception);
        }
    }

    private static byte[] read(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("document_template_jrxml_required");
        }
        if (file.getSize() > SafeJrxmlCompiler.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("document_template_jrxml_too_large");
        }
        String filename = file.getOriginalFilename();
        if (filename != null && !filename.isBlank()
                && !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".jrxml")) {
            throw new IllegalArgumentException("document_template_jrxml_extension_required");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("document_template_jrxml_read_failed", exception);
        }
    }

    private void deleteArtifactOnRollback(String reference) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            try {
                                storage.delete(reference);
                            } catch (IOException ignored) {
                                // The database rollback remains authoritative; orphan cleanup is recoverable.
                            }
                        }
                    }
                });
    }

    private static LinkedHashMap<String, Object> auditDetails(DocumentTemplate template) {
        var details = new LinkedHashMap<String, Object>();
        details.put("templateId", template.getId().toString());
        details.put("scope", template.getScope().name());
        details.put("type", template.getType().name());
        details.put("code", template.getCode());
        details.put("templateVersion", template.getTemplateVersion());
        details.put("status", template.getStatus().name());
        details.put("sha256", template.getSha256());
        return details;
    }

    public record SourceDownload(String filename, byte[] content) {

        public SourceDownload {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}

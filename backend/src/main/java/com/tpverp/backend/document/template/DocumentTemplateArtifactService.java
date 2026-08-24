package com.tpverp.backend.document.template;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TicketJrxmlBundleCompiler ticketCompiler;
    private final DocumentTemplateArtifactStorage storage;
    private final DocumentTemplateCatalogService catalog;
    private final AuditService audit;
    private final Clock clock;

    public DocumentTemplateArtifactService(
            DocumentTemplateRepository templates,
            CurrentOrganization organization,
            SafeJrxmlCompiler compiler,
            TicketJrxmlBundleCompiler ticketCompiler,
            DocumentTemplateArtifactStorage storage,
            DocumentTemplateCatalogService catalog,
            AuditService audit,
            Clock clock) {
        this.templates = templates;
        this.organization = organization;
        this.compiler = compiler;
        this.ticketCompiler = ticketCompiler;
        this.storage = storage;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public DocumentTemplateCatalogService.TemplateView uploadAndValidate(
            UUID templateId, List<MultipartFile> files) {
        Objects.requireNonNull(files, "files");
        var template = currentStoreTemplateForUpdate(templateId);
        if (template.getStatus() != DocumentTemplateStatus.DRAFT) {
            throw new IllegalStateException("document_template_not_draft");
        }
        DocumentTemplateArtifactStorage.StoredArtifact artifact;
        String sha256;
        try {
            if (template.getType() == DocumentTemplateType.TICKET) {
                var compiled = ticketCompiler.compileUpload(readBundle(files));
                artifact = storage.writeBundle(template.getId(),
                        TicketJrxmlBundleCompiler.MASTER_FILENAME, compiled.reports());
                deleteArtifactOnRollback(artifact.reference());
                sha256 = compiled.sha256();
                var sources = new LinkedHashMap<String, byte[]>();
                compiled.reports().forEach((name, report) -> sources.put(name, report.source()));
                FiscalJrxmlConformityValidator.require(sources, template.getType());
            } else {
                if (files.size() != 1) {
                    throw new IllegalArgumentException(
                            "document_template_single_jrxml_required");
                }
                var compiled = compiler.compile(read(files.getFirst()));
                artifact = storage.write(
                        template.getId(), compiled.source(), compiled.compiled());
                deleteArtifactOnRollback(artifact.reference());
                sha256 = compiled.sha256();
                FiscalJrxmlConformityValidator.require(
                        Map.of("template.jrxml", compiled.source()), template.getType());
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "document_template_artifact_storage_failed", exception);
        }
        template.validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                artifact.reference(),
                sha256,
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
            if (storage.isBundle(template.getArtifactReference())) {
                return new SourceDownload(
                        template.getCode().toLowerCase(java.util.Locale.ROOT)
                                + "_v" + template.getTemplateVersion() + ".zip",
                        "application/zip",
                        zip(storage.readBundleSources(template.getArtifactReference())));
            }
            return new SourceDownload(
                    template.getCode().toLowerCase(java.util.Locale.ROOT)
                            + "_v" + template.getTemplateVersion() + ".jrxml",
                    "application/xml",
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

    public DocumentTemplateCatalogService.TemplateView reactivate(UUID templateId) {
        var store = organization.currentStore();
        var template = templates.findStoreTemplate(
                        templateId, store.getEmpresa().getId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "document_template_not_found"));
        if (!template.canReactivate()) {
            throw new IllegalStateException("document_template_not_reactivatable");
        }
        verifyStoredArtifact(template);
        return catalog.reactivateCurrentStoreTemplate(templateId);
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
            if (storage.isBundle(template.getArtifactReference())) {
                var sources = storage.readBundleSources(template.getArtifactReference());
                if (!TicketJrxmlBundleCompiler.bundleSha256(sources)
                        .equals(template.getSha256())) {
                    throw new IllegalStateException(
                            "document_template_artifact_integrity_failed");
                }
                ticketCompiler.compile(sources);
                FiscalJrxmlConformityValidator.require(sources, template.getType());
                return;
            }
            byte[] source = storage.readSource(template.getArtifactReference());
            byte[] compiled = storage.readCompiled(template.getArtifactReference());
            if (compiled.length == 0
                    || !SafeJrxmlCompiler.sha256(source).equals(template.getSha256())) {
                throw new IllegalStateException("document_template_artifact_integrity_failed");
            }
            compiler.compile(source);
            FiscalJrxmlConformityValidator.require(
                    Map.of("template.jrxml", source), template.getType());
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

    private static byte[] zip(Map<String, byte[]> sources) throws IOException {
        try (var output = new java.io.ByteArrayOutputStream();
                var zip = new java.util.zip.ZipOutputStream(output)) {
            for (var entry : new java.util.TreeMap<>(sources).entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private static Map<String, byte[]> readBundle(List<MultipartFile> files) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("document_template_jrxml_required");
        }
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalSize > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("document_template_ticket_bundle_too_large");
        }
        if (files.size() == 1) {
            // A user-provided ticket master may have any .jrxml filename. The
            // stored bundle uses the canonical name expected by the renderer.
            return Map.of(TicketJrxmlBundleCompiler.MASTER_FILENAME,
                    read(files.getFirst()));
        }
        var sources = new LinkedHashMap<String, byte[]>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.matches(
                    "[A-Za-z0-9][A-Za-z0-9_-]*\\.jrxml")) {
                throw new IllegalArgumentException(
                        "document_template_bundle_filename_invalid");
            }
            if (sources.putIfAbsent(filename, read(file)) != null) {
                throw new IllegalArgumentException(
                        "document_template_bundle_filename_duplicated");
            }
        }
        return Map.copyOf(sources);
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

    public record SourceDownload(String filename, String contentType, byte[] content) {

        public SourceDownload {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}

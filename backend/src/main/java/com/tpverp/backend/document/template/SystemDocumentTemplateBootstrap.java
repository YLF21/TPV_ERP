package com.tpverp.backend.document.template;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class SystemDocumentTemplateBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(
            SystemDocumentTemplateBootstrap.class);
    private static final long BOOTSTRAP_LOCK = 607_658_690_841_410L;
    // Changing a bundled JRXML requires incrementing its version. Installed versions are immutable.
    private static final List<Definition> BUNDLED_TEMPLATES = List.of(
            new Definition(
                    DocumentTemplateType.FACTURA_VENTA,
                    "FACTURA_A4",
                    1,
                    "Factura de venta A4",
                    "document-templates/factura_venta_a4.jrxml"),
            new Definition(
                    DocumentTemplateType.ALBARAN_VENTA,
                    "ALBARAN_A4",
                    1,
                    "Albaran de venta A4",
                    "document-templates/albaran_venta_a4.jrxml"));

    private final DocumentTemplateRepository templates;
    private final DocumentTemplateArtifactStorage storage;
    private final SafeJrxmlCompiler compiler;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final List<Definition> definitions;

    @Autowired
    SystemDocumentTemplateBootstrap(
            DocumentTemplateRepository templates,
            DocumentTemplateArtifactStorage storage,
            SafeJrxmlCompiler compiler,
            Clock clock,
            JdbcTemplate jdbc) {
        this(templates, storage, compiler, clock, jdbc, BUNDLED_TEMPLATES);
    }

    SystemDocumentTemplateBootstrap(
            DocumentTemplateRepository templates,
            DocumentTemplateArtifactStorage storage,
            SafeJrxmlCompiler compiler,
            Clock clock,
            JdbcTemplate jdbc,
            List<Definition> definitions) {
        this.templates = templates;
        this.storage = storage;
        this.compiler = compiler;
        this.clock = clock;
        this.jdbc = jdbc;
        this.definitions = List.copyOf(definitions);
    }

    @Transactional
    public void initialize() {
        jdbc.execute("select pg_advisory_xact_lock(" + BOOTSTRAP_LOCK + ")");
        for (var definition : definitions) {
            install(definition);
        }
    }

    private void install(Definition definition) {
        byte[] source = read(definition.resource());
        String sourceHash = SafeJrxmlCompiler.sha256(source);
        var existing = templates.findSystemTemplate(
                definition.code(), definition.version());
        if (existing.isPresent()) {
            verifyAndRepair(existing.get(), definition, source, sourceHash);
            activateIfNewer(existing.get(), definition);
            return;
        }

        var compiled = compiler.compile(source);
        var template = DocumentTemplate.systemDraft(
                definition.type(),
                definition.code(),
                definition.version(),
                definition.name(),
                null,
                clock.instant());
        templates.saveAndFlush(template);
        var artifact = writeArtifact(template, compiled);
        deleteArtifactOnRollback(artifact.reference());
        var now = clock.instant();
        template.validateArtifact(
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                artifact.reference(),
                compiled.sha256(),
                now);
        templates.saveAndFlush(template);
        activateIfNewer(template, definition);
        LOG.info("Installed bundled document template {} v{} ({})",
                definition.code(), definition.version(), definition.type());
    }

    private void verifyAndRepair(
            DocumentTemplate template,
            Definition definition,
            byte[] source,
            String sourceHash) {
        if (template.getType() != definition.type()
                || template.getScope() != DocumentTemplateScope.SYSTEM) {
            throw new IllegalStateException(
                    "system_document_template_definition_conflict");
        }
        if (template.getStatus() == DocumentTemplateStatus.DRAFT) {
            var compiled = compiler.compile(source);
            var artifact = writeArtifact(template, compiled);
            deleteArtifactOnRollback(artifact.reference());
            template.validateArtifact(
                    SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                    template.getId().toString(),
                    compiled.sha256(),
                    clock.instant());
            templates.saveAndFlush(template);
            return;
        }
        if (!sourceHash.equals(template.getSha256())) {
            throw new IllegalStateException(
                    "system_document_template_version_conflict");
        }
        if (!template.getId().toString().equals(template.getArtifactReference())) {
            throw new IllegalStateException(
                    "system_document_template_artifact_reference_invalid");
        }
        if (!artifactMatches(template, sourceHash)) {
            writeArtifact(template, compiler.compile(source));
            LOG.warn("Repaired bundled document template artifact {} v{}",
                    definition.code(), definition.version());
        }
    }

    private boolean artifactMatches(DocumentTemplate template, String expectedHash) {
        try {
            byte[] storedSource = storage.readSource(template.getArtifactReference());
            byte[] storedCompiled = storage.readCompiled(template.getArtifactReference());
            return storedCompiled.length > 0
                    && expectedHash.equals(SafeJrxmlCompiler.sha256(storedSource));
        } catch (IOException exception) {
            return false;
        }
    }

    private DocumentTemplateArtifactStorage.StoredArtifact writeArtifact(
            DocumentTemplate template,
            SafeJrxmlCompiler.CompiledTemplate compiled) {
        try {
            return storage.write(
                    template.getId(), compiled.source(), compiled.compiled());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "system_document_template_artifact_storage_failed", exception);
        }
    }

    private void activateIfNewer(
            DocumentTemplate template,
            Definition definition) {
        if (template.getStatus() == DocumentTemplateStatus.ACTIVE
                || template.getStatus() == DocumentTemplateStatus.RETIRED) {
            return;
        }
        var active = templates.findActiveSystemTemplateForUpdate(definition.type());
        if (active.isPresent()) {
            var current = active.get();
            if (current.getTemplateVersion() >= definition.version()) {
                return;
            }
            current.retire(clock.instant());
            templates.saveAndFlush(current);
        }
        template.activate(clock.instant());
        templates.saveAndFlush(template);
    }

    private static byte[] read(String resourcePath) {
        try (var input = new ClassPathResource(resourcePath).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "system_document_template_resource_read_failed", exception);
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
                            } catch (IOException exception) {
                                LOG.warn("Could not remove rolled back system template artifact {}",
                                        reference, exception);
                            }
                        }
                    }
                });
    }

    record Definition(
            DocumentTemplateType type,
            String code,
            int version,
            String name,
            String resource) {

        Definition {
            Objects.requireNonNull(type, "type");
            DocumentTemplate.normalizeCode(code);
            if (version <= 0) {
                throw new IllegalArgumentException(
                        "system_document_template_version_invalid");
            }
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(resource, "resource");
        }
    }
}

package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemDocumentTemplateBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");

    @TempDir Path tempDir;

    @Test
    void installsTheBundledTemplatesAndIsIdempotentOnRestart() throws Exception {
        var templates = mock(DocumentTemplateRepository.class);
        var stored = new LinkedHashMap<UUID, DocumentTemplate>();
        configureRepository(templates, stored);
        var storage = new DocumentTemplateArtifactStorage(tempDir);
        var bootstrap = bootstrap(templates, storage);

        bootstrap.initialize();
        bootstrap.initialize();

        assertThat(stored.values())
                .hasSize(2)
                .allSatisfy(template -> {
                    assertThat(template.getScope()).isEqualTo(DocumentTemplateScope.SYSTEM);
                    assertThat(template.getStatus()).isEqualTo(DocumentTemplateStatus.ACTIVE);
                    assertThat(template.getTemplateVersion()).isEqualTo(1);
                    assertThat(storage.readSource(template.getArtifactReference())).isNotEmpty();
                    assertThat(storage.readCompiled(template.getArtifactReference())).isNotEmpty();
                });
        assertThat(stored.values()).extracting(DocumentTemplate::getCode)
                .containsExactlyInAnyOrder("FACTURA_A4", "ALBARAN_A4");
        verify(templates, times(6)).saveAndFlush(any(DocumentTemplate.class));
    }

    @Test
    void doesNotReplaceANewerActiveSystemTemplate() {
        var templates = mock(DocumentTemplateRepository.class);
        var newer = activeSystemTemplate(
                DocumentTemplateType.FACTURA_VENTA, "FACTURA_A4", 2);
        var stored = new LinkedHashMap<UUID, DocumentTemplate>();
        stored.put(newer.getId(), newer);
        configureRepository(templates, stored);

        bootstrap(templates, new DocumentTemplateArtifactStorage(tempDir)).initialize();

        assertThat(newer.getStatus()).isEqualTo(DocumentTemplateStatus.ACTIVE);
        assertThat(stored.values().stream()
                .filter(template -> template.getType() == DocumentTemplateType.FACTURA_VENTA)
                .filter(template -> template.getTemplateVersion() == 1)
                .findFirst())
                .get()
                .extracting(DocumentTemplate::getStatus)
                .isEqualTo(DocumentTemplateStatus.VALIDATED);
    }

    @Test
    void activatesANewerBundledVersionAndRetiresThePreviousSystemVersion()
            throws Exception {
        var templates = mock(DocumentTemplateRepository.class);
        var storage = new DocumentTemplateArtifactStorage(tempDir);
        byte[] source = new ClassPathResource(
                "document-templates/factura_venta_a4.jrxml")
                .getContentAsByteArray();
        var compiled = new SafeJrxmlCompiler().compile(source);
        var current = DocumentTemplate.systemDraft(
                DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_A4",
                1,
                "Factura A4 v1",
                null,
                NOW.minusSeconds(20));
        var currentArtifact = storage.write(
                current.getId(), compiled.source(), compiled.compiled());
        current.validateArtifact(
                1,
                currentArtifact.reference(),
                compiled.sha256(),
                NOW.minusSeconds(10));
        current.activate(NOW.minusSeconds(5));
        var stored = new LinkedHashMap<UUID, DocumentTemplate>();
        stored.put(current.getId(), current);
        configureRepository(templates, stored);
        var definition = new SystemDocumentTemplateBootstrap.Definition(
                DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_A4",
                2,
                "Factura A4 v2",
                "document-templates/factura_venta_a4.jrxml");
        var bootstrap = new SystemDocumentTemplateBootstrap(
                templates,
                storage,
                new SafeJrxmlCompiler(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(JdbcTemplate.class),
                java.util.List.of(definition));

        bootstrap.initialize();

        assertThat(current.getStatus()).isEqualTo(DocumentTemplateStatus.RETIRED);
        assertThat(stored.values().stream()
                .filter(template -> template.getTemplateVersion() == 2)
                .findFirst())
                .get()
                .satisfies(template -> {
                    assertThat(template.getStatus()).isEqualTo(DocumentTemplateStatus.ACTIVE);
                    assertThat(template.getType()).isEqualTo(
                            DocumentTemplateType.FACTURA_VENTA);
                });
    }

    @Test
    void rejectsChangingTheContentsOfAnAlreadyInstalledVersion() {
        var templates = mock(DocumentTemplateRepository.class);
        var existing = activeSystemTemplate(
                DocumentTemplateType.FACTURA_VENTA, "FACTURA_A4", 1);
        when(templates.findSystemTemplate("FACTURA_A4", 1))
                .thenReturn(Optional.of(existing));
        var bootstrap = bootstrap(
                templates, new DocumentTemplateArtifactStorage(tempDir));

        assertThatThrownBy(bootstrap::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("system_document_template_version_conflict");
    }

    private SystemDocumentTemplateBootstrap bootstrap(
            DocumentTemplateRepository templates,
            DocumentTemplateArtifactStorage storage) {
        return new SystemDocumentTemplateBootstrap(
                templates,
                storage,
                new SafeJrxmlCompiler(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(JdbcTemplate.class));
    }

    private static void configureRepository(
            DocumentTemplateRepository templates,
            LinkedHashMap<UUID, DocumentTemplate> stored) {
        when(templates.findSystemTemplate(anyString(), anyInt()))
                .thenAnswer(invocation -> stored.values().stream()
                        .filter(template -> template.getCode().equals(invocation.getArgument(0)))
                        .filter(template -> template.getTemplateVersion()
                                == (int) invocation.getArgument(1))
                        .findFirst());
        when(templates.findActiveSystemTemplateForUpdate(any()))
                .thenAnswer(invocation -> stored.values().stream()
                        .filter(template -> template.getType() == invocation.getArgument(0))
                        .filter(template -> template.getStatus() == DocumentTemplateStatus.ACTIVE)
                        .findFirst());
        when(templates.saveAndFlush(any(DocumentTemplate.class)))
                .thenAnswer(invocation -> {
                    DocumentTemplate template = invocation.getArgument(0);
                    stored.put(template.getId(), template);
                    return template;
                });
    }

    private static DocumentTemplate activeSystemTemplate(
            DocumentTemplateType type,
            String code,
            int version) {
        var template = DocumentTemplate.systemDraft(
                type, code, version, "System", null, NOW.minusSeconds(20));
        template.validateArtifact(
                1,
                template.getId().toString(),
                "a".repeat(64),
                NOW.minusSeconds(10));
        template.activate(NOW.minusSeconds(5));
        return template;
    }
}

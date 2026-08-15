package com.tpverp.backend.document.template;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@WebMvcTest(DocumentTemplateController.class)
@Import(DocumentTemplateControllerTest.MethodSecurityConfiguration.class)
class DocumentTemplateControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentTemplateCatalogService service;
    @MockitoBean private DocumentTemplateArtifactService artifacts;

    @Test
    void listsCatalogWithoutAnEffectiveTemplateSoItCanBeConfigured() throws Exception {
        when(service.currentStoreCatalog(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4))
                .thenReturn(new DocumentTemplateCatalogService.CatalogView(
                        null,
                        List.of()));

        mvc.perform(get("/api/v1/document-templates")
                        .with(user("manager").authorities(
                                () -> "APP_GESTION_ACCESS",
                                () -> "DOCUMENT_TEMPLATES_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").doesNotExist());
    }

    @Test
    void rejectsPermissionWithoutAppGestionAccess() throws Exception {
        mvc.perform(get("/api/v1/document-templates")
                        .with(user("manager").authorities(
                                () -> "DOCUMENT_TEMPLATES_MANAGE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanRegisterStoreDraftMetadata() throws Exception {
        var view = new DocumentTemplateCatalogService.TemplateView(
                UUID.randomUUID(), DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4,
                DocumentTemplateScope.STORE, "FACTURA_LP", 1, "Factura LP",
                DocumentTemplateStatus.DRAFT, null, null, null,
                Instant.parse("2026-08-09T10:00:00Z"), null, null, null, false);
        when(service.registerCurrentStoreDraft(
                DocumentTemplateType.FACTURA_VENTA, DocumentTemplateFormat.A4,
                "FACTURA_LP", "Factura LP"))
                .thenReturn(view);

        mvc.perform(post("/api/v1/document-templates/store-drafts")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "FACTURA_VENTA",
                                  "format": "A4",
                                  "code": "FACTURA_LP",
                                  "name": "Factura LP"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FACTURA_LP"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void managerCanUploadAStoreJrxmlAndActivateIt() throws Exception {
        var templateId = UUID.randomUUID();
        var validated = new DocumentTemplateCatalogService.TemplateView(
                templateId, DocumentTemplateType.FACTURA_VENTA,
                DocumentTemplateFormat.A4,
                DocumentTemplateScope.STORE, "FACTURA_LP", 1, "Factura LP",
                DocumentTemplateStatus.VALIDATED, 1, "a".repeat(64), null,
                Instant.parse("2026-08-09T10:00:00Z"),
                Instant.parse("2026-08-09T10:01:00Z"), null, null, false);
        when(artifacts.uploadAndValidate(
                org.mockito.ArgumentMatchers.eq(templateId),
                org.mockito.ArgumentMatchers.any())).thenReturn(validated);
        when(artifacts.activate(templateId))
                .thenReturn(new DocumentTemplateCatalogService.TemplateView(
                        templateId, DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.A4,
                        DocumentTemplateScope.STORE, "FACTURA_LP", 1, "Factura LP",
                        DocumentTemplateStatus.ACTIVE, 1, "a".repeat(64), null,
                        Instant.parse("2026-08-09T10:00:00Z"),
                        Instant.parse("2026-08-09T10:01:00Z"),
                        Instant.parse("2026-08-09T10:02:00Z"), null, false));
        var file = new MockMultipartFile(
                "files", "factura.jrxml", "application/xml", "<jasperReport/>".getBytes());

        mvc.perform(multipart("/api/v1/document-templates/{id}/artifact", templateId)
                        .file(file)
                        .with(user("manager").authorities(
                                () -> "APP_GESTION_ACCESS",
                                () -> "DOCUMENT_TEMPLATES_MANAGE"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        mvc.perform(post("/api/v1/document-templates/{id}/activate", templateId)
                        .with(user("manager").authorities(
                                () -> "APP_GESTION_ACCESS",
                                () -> "DOCUMENT_TEMPLATES_MANAGE"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(artifacts).activate(templateId);
    }

    @Test
    void managerCanReactivateARecoverableRetiredVersion() throws Exception {
        var templateId = UUID.randomUUID();
        when(artifacts.reactivate(templateId))
                .thenReturn(new DocumentTemplateCatalogService.TemplateView(
                        templateId, DocumentTemplateType.TICKET,
                        DocumentTemplateFormat.TICKET_80,
                        DocumentTemplateScope.STORE, "TICKET_80", 2, "Mi ticket",
                        DocumentTemplateStatus.ACTIVE, 1, "a".repeat(64), null,
                        Instant.parse("2026-08-09T10:00:00Z"),
                        Instant.parse("2026-08-09T10:01:00Z"),
                        Instant.parse("2026-08-09T10:02:00Z"), null, false));

        mvc.perform(post("/api/v1/document-templates/{id}/reactivate", templateId)
                        .with(user("manager").authorities(
                                () -> "APP_GESTION_ACCESS",
                                () -> "DOCUMENT_TEMPLATES_MANAGE"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reactivatable").value(false));

        verify(artifacts).reactivate(templateId);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

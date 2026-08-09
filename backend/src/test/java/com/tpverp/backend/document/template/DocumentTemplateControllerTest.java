package com.tpverp.backend.document.template;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(DocumentTemplateController.class)
@Import(DocumentTemplateControllerTest.MethodSecurityConfiguration.class)
class DocumentTemplateControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentTemplateCatalogService service;

    @Test
    void listsCatalogWithDedicatedManagementPermission() throws Exception {
        when(service.currentStoreCatalog(DocumentTemplateType.FACTURA_VENTA))
                .thenReturn(new DocumentTemplateCatalogService.CatalogView(
                        ResolvedDocumentTemplate.builtIn(DocumentTemplateType.FACTURA_VENTA),
                        List.of()));

        mvc.perform(get("/api/v1/document-templates")
                        .with(user("manager").authorities(
                                () -> "APP_GESTION_ACCESS",
                                () -> "DOCUMENT_TEMPLATES_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.code").value("FACTURA_A4"))
                .andExpect(jsonPath("$.effective.builtIn").value(true));
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
                DocumentTemplateScope.STORE, "FACTURA_LP", 1, "Factura LP",
                DocumentTemplateStatus.DRAFT, null, null, null,
                Instant.parse("2026-08-09T10:00:00Z"), null, null, null);
        when(service.registerCurrentStoreDraft(
                DocumentTemplateType.FACTURA_VENTA, "FACTURA_LP", "Factura LP"))
                .thenReturn(view);

        mvc.perform(post("/api/v1/document-templates/store-drafts")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "FACTURA_VENTA",
                                  "code": "FACTURA_LP",
                                  "name": "Factura LP"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FACTURA_LP"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

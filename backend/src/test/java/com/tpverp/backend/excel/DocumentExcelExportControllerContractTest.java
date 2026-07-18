package com.tpverp.backend.excel;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DocumentExcelExportController.class)
@Import(DocumentExcelExportControllerContractTest.MethodSecurityConfiguration.class)
class DocumentExcelExportControllerContractTest {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentExcelExportService service;

    @Test
    void exportsOneDocument() throws Exception {
        var id = UUID.randomUUID();
        when(service.export(id)).thenReturn(new byte[] {1, 2, 3});

        mvc.perform(get("/api/v1/excel/documents/{id}/export", id)
                        .with(user("ADMIN").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"documento.xlsx\""));
    }

    @Test
    void exportsBatch() throws Exception {
        var id = UUID.randomUUID();
        when(service.export(List.of(id))).thenReturn(new byte[] {1});

        mvc.perform(post("/api/v1/excel/documents/export")
                        .with(user("ADMIN").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIds\":[\"" + id + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX));
    }

    @Test
    void salesManagementCanExportButPosSalesCannot() throws Exception {
        var id = UUID.randomUUID();
        when(service.export(id)).thenReturn(new byte[] {1});

        mvc.perform(get("/api/v1/excel/documents/{id}/export", id)
                        .with(user("manager").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/excel/documents/{id}/export", id)
                        .with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

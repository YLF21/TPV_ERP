package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FiscalResponsibleDeclarationController.class)
@Import(FiscalResponsibleDeclarationControllerWebMvcTest.MethodSecurityConfiguration.class)
class FiscalResponsibleDeclarationControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FiscalResponsibleDeclarationService declarations;

    @Test
    void anyAuthenticatedUserCanReadMetadataAndPdfWithSafeHeaders() throws Exception {
        var bytes = "%PDF-1.4\nfake\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var loaded = new FiscalResponsibleDeclarationService.LoadedDeclaration(
                bytes, "declaracion-responsable-4.2.0.pdf", "A".repeat(64),
                Instant.parse("2026-08-27T00:00:00Z"), null);
        when(declarations.status()).thenReturn(new FiscalResponsibleDeclarationService.ResponsibleDeclarationStatus(
                "AVAILABLE", "4.2.0", "release-4.2.0", loaded.fileName(),
                MediaType.APPLICATION_PDF_VALUE, (long) bytes.length, loaded.sha256(),
                loaded.issuedAt(), FiscalResponsibleDeclarationService.DOWNLOAD_URL));
        when(declarations.content()).thenReturn(loaded);

        mvc.perform(get("/api/v1/fiscal/responsible-declaration").with(user("cashier")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.releaseId").value("release-4.2.0"));
        mvc.perform(get(FiscalResponsibleDeclarationService.DOWNLOAD_URL).with(user("cashier")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + loaded.sha256() + "\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("declaracion-responsable-4.2.0.pdf")))
                .andExpect(content -> org.assertj.core.api.Assertions.assertThat(content.getResponse().getContentAsByteArray())
                        .containsExactly(bytes));
    }

    @Test
    void anonymousUserIsRejected() throws Exception {
        mvc.perform(get("/api/v1/fiscal/responsible-declaration"))
                .andExpect(status().isUnauthorized());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

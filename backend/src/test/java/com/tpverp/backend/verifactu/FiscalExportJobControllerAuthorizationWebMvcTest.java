package com.tpverp.backend.verifactu;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import com.tpverp.backend.organization.CurrentOrganization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FiscalExportJobController.class)
@Import(FiscalExportJobControllerAuthorizationWebMvcTest.MethodSecurityConfiguration.class)
class FiscalExportJobControllerAuthorizationWebMvcTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FiscalExportJobService jobs;
    @MockitoBean
    private FiscalExportJobLauncher launcher;
    @MockitoBean
    private CurrentOrganization organization;

    @Test
    void fiscalReadOnlyCannotCreateOrInspectExportJobs() throws Exception {
        mvc.perform(post("/api/v1/fiscal/export-jobs")
                        .with(user("reader").authorities(new SimpleGrantedAuthority("APP_GESTION_ACCESS"),
                                new SimpleGrantedAuthority("VERIFACTU_READ")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BILLING\",\"scope\":\"CURRENT\",\"recordIds\":[\""
                                + java.util.UUID.randomUUID() + "\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fiscal/export-jobs/" + java.util.UUID.randomUUID())
                        .with(user("reader").authorities(Arrays.stream(new String[] {
                                "APP_GESTION_ACCESS", "VERIFACTU_READ"})
                                .map(SimpleGrantedAuthority::new).toList())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fiscal/export-jobs/" + java.util.UUID.randomUUID() + "/retry")
                        .with(user("reader").authorities(new SimpleGrantedAuthority("APP_GESTION_ACCESS"),
                                new SimpleGrantedAuthority("VERIFACTU_READ")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}

package com.tpverp.backend.verifactu;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FiscalSandboxController.class)
@ActiveProfiles("fiscal-dev")
@Import(FiscalSandboxControllerWebMvcTest.MethodSecurityConfiguration.class)
class FiscalSandboxControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FiscalRuntimeProperties runtime;
    @MockitoBean
    private SimulatedAeatTransport simulator;
    @MockitoBean
    private VerifactuSubmissionWorker worker;

    @Test
    void scenarioSinResultadoDevuelveBadRequest() throws Exception {
        org.mockito.Mockito.when(runtime.isSandbox()).thenReturn(true);
        org.mockito.Mockito.when(runtime.transportMode()).thenReturn(FiscalTransportMode.SIMULATED);

        mvc.perform(put("/api/v1/dev/fiscal-sandbox/scenario")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(simulator);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

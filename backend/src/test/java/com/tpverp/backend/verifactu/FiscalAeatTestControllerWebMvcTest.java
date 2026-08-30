package com.tpverp.backend.verifactu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FiscalAeatTestController.class)
@Import(FiscalAeatTestControllerWebMvcTest.MethodSecurityConfiguration.class)
class FiscalAeatTestControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @MockitoBean FiscalAeatTestDispatchService dispatch;

    @Test
    void anonymousIsRejectedAndServiceIsNotCalled() throws Exception {
        mvc.perform(post("/api/v1/dev/fiscal-aeat-test/dispatch-next")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/dev/fiscal-aeat-test/dispatch-next")
                        .with(user("gestion").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"installationId\":\"00000000-0000-0000-0000-000000000002\","
                                + "\"expectedReleaseId\":\"DEV-TEST-20260827\","
                                + "\"confirmation\":\"CONFIRMAR_AEAT_TEST\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminReachesEndpointContract() throws Exception {
        var response = new FiscalAeatTestDispatchView(
                false, null, null, null,
                new FiscalAeatTestDispatchView.EvidenceMetadata(
                        "DEV-TEST-20260827", FiscalRuntimeClass.SANDBOX,
                        FiscalEndpointEnvironment.TEST, FiscalTransportMode.AEAT,
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null,
                        false, true));
        when(dispatch.dispatch(any(), any())).thenReturn(response);

        mvc.perform(post("/api/v1/dev/fiscal-aeat-test/dispatch-next")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"installationId\":\"00000000-0000-0000-0000-000000000002\","
                                + "\"expectedReleaseId\":\"DEV-TEST-20260827\","
                                + "\"confirmation\":\"CONFIRMAR_AEAT_TEST\"}"))
                .andExpect(status().isOk());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

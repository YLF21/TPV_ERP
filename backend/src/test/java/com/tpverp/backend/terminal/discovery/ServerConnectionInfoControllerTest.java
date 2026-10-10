package com.tpverp.backend.terminal.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ServerConnectionInfoControllerTest.Config.class)
class ServerConnectionInfoControllerTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean ServerConnectionInfoController controller(ServerConnectionInfoService service) {
            return new ServerConnectionInfoController(service);
        }
    }
    @MockitoBean ServerConnectionInfoService service;
    @Autowired ServerConnectionInfoController controller;

    @Test @WithMockUser(authorities = "VENTA")
    void cashierCannotReadServerNetworkDetails() {
        assertThatThrownBy(() -> controller.read(new MockHttpServletRequest())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test @WithMockUser(authorities = "TERMINALS_MANAGE")
    void readsTheActualLocalPortWithoutTrustingForwardedPortHeaders() {
        var request = new MockHttpServletRequest();
        request.setLocalPort(18080); request.setServerPort(18443);
        request.setRemoteAddr("192.168.82.101");
        request.addHeader("X-Forwarded-Port", "9999");
        var info = new ServerConnectionInfoService.ConnectionInfo(List.of("192.168.82.2"), 18443, 18080, "https://tpv:18443");
        when(service.read(18080)).thenReturn(info);
        assertThat(controller.read(request)).isEqualTo(info);
        verify(service).read(18080);
    }

    @Test @WithMockUser(roles = "ADMIN")
    void servesReadOnlyJsonOnTheTerminalsRoute() throws Exception {
        when(service.read(18080)).thenReturn(new ServerConnectionInfoService.ConnectionInfo(
                List.of("192.168.31.46"), 18443, 18080, "https://tpv:18443"));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/v1/terminals/server-connection").with(request -> { request.setLocalPort(18080); return request; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.addresses[0]").value("192.168.31.46"))
                .andExpect(jsonPath("$.httpsPort").value(18443)).andExpect(jsonPath("$.backendPort").value(18080));
    }

    @Test
    void retainsTheSameSecurityGroupAsTerminalManagement() throws Exception {
        assertThat(ServerConnectionInfoController.class.getMethod("read", HttpServletRequest.class)
                .getAnnotation(RequireGestionGroup.class).value()).isEqualTo(GestionGroup.SEGURIDAD);
    }
}

package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WorkstationMethodSecurityTest.Config.class)
class WorkstationMethodSecurityTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean WorkstationController controller(TerminalLinkingService service) { return new WorkstationController(service); }
    }
    @MockitoBean TerminalLinkingService service;
    @Autowired WorkstationController controller;
    @Test @WithMockUser(authorities = "VENTA")
    void cashierCannotReadOrMutateWorkstations() {
        assertThatThrownBy(controller::list).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.action("002", "release", new WorkstationController.ExpectedBinding(java.util.UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }
    @Test @WithMockUser(authorities = "TERMINALS_MANAGE")
    void terminalManagerCanReachTheManagementService() {
        var expected = new TerminalLinkingService.ManagementView(1, List.of(), List.of());
        when(service.management()).thenReturn(expected);
        assertThat(controller.list()).isEqualTo(expected);
    }
}

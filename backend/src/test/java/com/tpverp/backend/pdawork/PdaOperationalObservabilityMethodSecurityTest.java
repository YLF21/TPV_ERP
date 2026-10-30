package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PdaOperationalObservabilityMethodSecurityTest.Config.class)
class PdaOperationalObservabilityMethodSecurityTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean PdaOperationalObservabilityController controller(PdaOperationalObservabilityService service) {
            return new PdaOperationalObservabilityController(service);
        }
    }

    @MockitoBean PdaOperationalObservabilityService service;
    @Autowired PdaOperationalObservabilityController controller;

    @Test @WithMockUser(roles = "PDA")
    void pdaRoleCanSubmitOperationalTelemetry() {
        UUID eventId = UUID.randomUUID();
        when(service.record(any(), any(), any())).thenReturn(
                new PdaOperationalObservabilityService.AcceptedEvent(eventId, Instant.now(), false));
        controller.record(request(), SecurityContextHolder.getContext().getAuthentication(), new MockHttpServletRequest());
        verify(service).record(any(), any(), any());
    }

    @Test @WithMockUser(authorities = "VENTA")
    void unrelatedRoleCannotSubmitOrReadTelemetry() {
        assertThatThrownBy(() -> controller.record(request(),
                SecurityContextHolder.getContext().getAuthentication(), new MockHttpServletRequest()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.dashboard(24, 30, 0, 50, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    private static PdaOperationalObservabilityController.EventRequest request() {
        return new PdaOperationalObservabilityController.EventRequest(UUID.randomUUID(),
                PdaOperationalEventType.RECOVERED, "PDA_QUEUE_SYNC_RECOVERED", null, null, Instant.now(), null);
    }
}

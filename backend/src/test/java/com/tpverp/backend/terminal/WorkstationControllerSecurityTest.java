package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class WorkstationControllerSecurityTest {
    @Test void everyManagementOperationRetainsPermissionAndSecurityGroup() throws Exception {
        for (var name : new String[] {"list", "action", "history", "assign"}) {
            var method = java.util.Arrays.stream(WorkstationController.class.getDeclaredMethods())
                    .filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
            assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')");
            assertThat(method.getAnnotation(RequireGestionGroup.class).value()).isEqualTo(GestionGroup.SEGURIDAD);
        }
        var adoption = WorkstationController.class.getDeclaredMethod("adopt", TerminalLinkingService.ServerAdoption.class,
                org.springframework.security.core.Authentication.class, HttpServletRequest.class);
        assertThat(adoption.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }
    @Test void loopbackDoesNotTrustForwardedAddresses() {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(TerminalLinkingController.loopback(request)).isFalse();
        request.setRemoteAddr("127.0.0.1");
        assertThat(TerminalLinkingController.loopback(request)).isFalse();
        request.removeHeader("X-Forwarded-For");
        assertThat(TerminalLinkingController.loopback(request)).isTrue();
    }
    @Test void genericAuthenticatedPrincipalCannotProvisionServer() {
        var service = mock(TerminalLinkingService.class);
        var controller = new WorkstationController(service);
        assertThatThrownBy(() -> controller.adopt(new TerminalLinkingService.ServerAdoption(UUID.randomUUID(), UUID.randomUUID(),
                "secret", "PC", null), new UsernamePasswordAuthenticationToken("ADMIN", "token"), new MockHttpServletRequest()))
                .hasMessage("INSTALLATION_ADMIN_REQUIRED");
        verifyNoInteractions(service);
    }
}

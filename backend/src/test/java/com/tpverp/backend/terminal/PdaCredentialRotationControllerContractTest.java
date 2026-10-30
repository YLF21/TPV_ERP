package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class PdaCredentialRotationControllerContractTest {
    @Test
    void returnsCredentialOnceInANonCacheableAuthenticatedResponse() {
        var registration = mock(TerminalRegistrationService.class);
        var rotation = mock(PdaCredentialRotationService.class);
        var controller = new TerminalController(registration, rotation);
        var authentication = new UsernamePasswordAuthenticationToken("operator", "token");
        var terminalId = UUID.randomUUID();
        var rotatedAt = Instant.parse("2026-10-29T18:00:00Z");
        when(rotation.rotate(authentication)).thenReturn(
                new PdaCredentialRotationService.RotationResult(
                        terminalId, "one-time-credential", rotatedAt));

        var response = controller.rotateCurrentPdaCredential(authentication);

        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(
                new PdaCredentialRotationService.RotationResult(
                        terminalId, "one-time-credential", rotatedAt));
    }
}

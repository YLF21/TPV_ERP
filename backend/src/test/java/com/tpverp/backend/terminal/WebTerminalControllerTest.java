package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.application.LoginResult;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class WebTerminalControllerTest {
    private static final String ORIGIN = "https://venta.example.test";
    private final TerminalLinkingService linking = mock(TerminalLinkingService.class);
    private final AuthenticationService auth = mock(AuthenticationService.class);
    private final WebTerminalCookie cookies = new WebTerminalCookie(new WebTerminalPolicy(true, ORIGIN, false));
    private final WebTerminalCookie.Proof proof = cookies.create();
    private final UUID terminalId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new WebTerminalController(new WebTerminalPolicy(true, ORIGIN, false),
                cookies, linking, auth)).setControllerAdvice(new Errors()).build();
        when(linking.bootstrap(anyString())).thenReturn(new TerminalLinkingService.Bootstrap(1,
                UUID.randomUUID(), "installation", UUID.randomUUID(), "Tienda", "Empresa", "public-key", "challenge", "signature", 3,
                List.of(new TerminalLinkingService.Slot("001", "RESERVED", null, null, null, null, false, null, null, null),
                        new TerminalLinkingService.Slot("002", "FREE", null, null, null, null, false, null, null, null)), "ABC"));
        when(linking.browserStatus(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test void statusInitializesAProofWithoutReservingAnySeatOrExposingTheProof() throws Exception {
        var result = mvc.perform(call("status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREGISTERED"))
                .andExpect(jsonPath("$.context").doesNotExist())
                .andExpect(jsonPath("$.slots[1].code").value("002"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("__Host-espos-terminal=", "HttpOnly", "Secure", "SameSite=Strict");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("credential", "public-key", "signature", "requestId", "deviceId");
        verify(linking, never()).request(any());
        verifyNoInteractions(auth);
    }

    @Test void registrationRequiresThePreviouslyStoredCookie() throws Exception {
        mvc.perform(call("request").content("{\"code\":\"002\",\"name\":\"Caja navegador\"}"))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("WEB_TERMINAL_COOKIE_REQUIRED"));
        verify(linking, never()).request(any());
    }

    @Test void requestsUseTheCookieProofAndWaitForAdministrativeApproval() throws Exception {
        when(linking.request(any())).thenReturn(state("PENDING"));
        mvc.perform(call("request").cookie(cookie()).content("{\"code\":\"002\",\"name\":\"Caja navegador\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.context").doesNotExist());
        var captured = ArgumentCaptor.forClass(TerminalLinkingService.LinkRequest.class);
        verify(linking).request(captured.capture());
        assertThat(captured.getValue().requestId()).isEqualTo(proof.requestId());
        assertThat(captured.getValue().deviceId()).isEqualTo(proof.deviceId());
        assertThat(captured.getValue().credential()).isEqualTo(proof.credential());
        assertThat(captured.getValue().code()).isEqualTo("002");
        verifyNoInteractions(auth);
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "DISABLED", "RELEASED", "CANCELLED", "EXPIRED"})
    void onlyAnActiveBindingCanProduceALoginOrAContext(String bindingStatus) throws Exception {
        when(linking.browserStatus(any(), any(), any())).thenReturn(Optional.of(state(bindingStatus)));
        mvc.perform(call("status").cookie(cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(bindingStatus)).andExpect(jsonPath("$.context").doesNotExist());
        mvc.perform(call("login").cookie(cookie()).content("{\"userName\":\"CAJERO\",\"password\":\"1234\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEB_TERMINAL_NOT_ACTIVE"));
        verifyNoInteractions(auth);
    }

    @Test void activeLoginUsesTheExistingAuthenticationServiceAndNeverReturnsTheTerminalProof() throws Exception {
        when(linking.browserStatus(any(), any(), any())).thenReturn(Optional.of(state("ACTIVE")));
        when(auth.login(eq(terminalId), eq(proof.credential()), eq("CAJERO"), eq("1234"), any()))
                .thenReturn(new LoginResult("bearer-token", UUID.randomUUID(), "CAJERO", "VENTA", false, BigDecimal.ZERO, Set.of("VENTA")));
        mvc.perform(call("status").cookie(cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.context.credentialMode").value("browser-cookie"))
                .andExpect(jsonPath("$.context.terminalId").value(terminalId.toString()));
        var result = mvc.perform(call("login").cookie(cookie()).content("{\"userName\":\"CAJERO\",\"password\":\"1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("bearer-token")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(proof.credential(), "terminalCredential");
        verify(auth).login(eq(terminalId), eq(proof.credential()), eq("CAJERO"), eq("1234"), any());
    }

    @Test void pendingCancellationRotatesTheBrowserProofAndDoesNotSelfReleaseAnActiveTerminal() throws Exception {
        when(linking.browserStatus(any(), any(), any())).thenReturn(Optional.of(state("PENDING")));
        var result = mvc.perform(call("cancel").cookie(cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREGISTERED")).andReturn();
        verify(linking).cancel(proof.linkingProof());
        assertThat(result.getResponse().getHeader("Set-Cookie")).doesNotContain(proof.credential());
        when(linking.browserStatus(any(), any(), any())).thenReturn(Optional.of(state("ACTIVE")));
        when(linking.cancel(any())).thenThrow(new TerminalLinkingException(HttpStatus.CONFLICT, "LINK_REQUEST_NOT_PENDING"));
        mvc.perform(call("cancel").cookie(cookie())).andExpect(status().isConflict());
        verify(linking, never()).action(any(), any(), any());
    }

    @Test void retiredProofRequiresASeparateResetBeforeReservingSoLostResponsesCannotOrphanASeat() throws Exception {
        when(linking.browserStatus(any(), any(), any())).thenReturn(Optional.of(state("RELEASED")));
        var result = mvc.perform(call("request").cookie(cookie()).content("{\"code\":\"002\",\"name\":\"Nueva caja\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEB_TERMINAL_RESET_REQUIRED")).andReturn();
        mvc.perform(call("request").cookie(cookie()).content("{\"code\":\"002\",\"name\":\"Nueva caja\"}"))
                .andExpect(status().isConflict());
        verify(linking, never()).request(any());
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        var reset = mvc.perform(call("cancel").cookie(cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNREGISTERED")).andReturn();
        assertThat(reset.getResponse().getHeader("Set-Cookie")).doesNotContain(proof.credential());
    }

    @Test void invalidExistingProofAndMalformedCookieFailClosed() throws Exception {
        when(linking.browserStatus(any(), any(), any())).thenThrow(new TerminalLinkingException(HttpStatus.FORBIDDEN, "LINK_PROOF_INVALID"));
        mvc.perform(call("status").cookie(cookie())).andExpect(status().isForbidden());
        mvc.perform(call("status").cookie(new Cookie("__Host-espos-terminal", "broken")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WEB_TERMINAL_PROOF_INVALID"));
        verify(linking, never()).request(any());
    }

    private MockHttpServletRequestBuilder call(String path) {
        return post("/api/v1/web-terminal/" + path).header("Origin", ORIGIN).header(WebTerminalPolicy.HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }
    private Cookie cookie() {
        var response = new MockHttpServletResponse(); cookies.write(response, proof);
        var header = response.getHeader("Set-Cookie");
        return new Cookie("__Host-espos-terminal", header.substring(header.indexOf('=') + 1, header.indexOf(';')));
    }
    private TerminalLinkingService.LinkState state(String status) {
        return new TerminalLinkingService.LinkState(proof.requestId(), UUID.randomUUID(), terminalId, "002", "Caja navegador",
                UUID.randomUUID(), "Tienda", UUID.randomUUID(), status, "PENDING".equals(status) ? Instant.now().plusSeconds(900) : null, "ABC");
    }
    @RestControllerAdvice static class Errors {
        @ExceptionHandler(TerminalLinkingException.class) ResponseEntity<Map<String, String>> linking(TerminalLinkingException exception) {
            return ResponseEntity.status(exception.status()).body(Map.of("code", exception.getMessage()));
        }
    }
}

package com.tpverp.backend.control;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ControlAlertController.class)
@Import(ControlAlertReopenControllerTest.MethodSecurityConfiguration.class)
class ControlAlertReopenControllerTest {

    private static final UUID ALERT_ID = UUID.fromString("f6593165-a13e-4a23-8657-063eb62fa3fc");
    private static final UUID USER_ID = UUID.fromString("af4f852a-a401-41fb-a2e1-19a747134195");
    private static final String PATH = "/api/v1/control/alerts/" + ALERT_ID + "/reopen";
    private static final Instant OCCURRED = Instant.parse("2026-09-16T08:00:00Z");
    private static final Instant REOPENED = Instant.parse("2026-09-17T10:00:00Z");
    private static final ControlAlertService.TransitionRequest REQUEST =
            new ControlAlertService.TransitionRequest(7L, "Reopen after checking the receipt");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BODY = JSON.writeValueAsString(REQUEST);
    private static final ControlAlertService.AlertDetailView VIEW = new ControlAlertService.AlertDetailView(
            new ControlAlertService.AlertSummaryView(ALERT_ID, ControlAlertStatus.NEW,
                    ControlAlertType.SALE_SCREEN_CLEARED, UUID.randomUUID(), 1, "Sale screen cleared",
                    null, null, UUID.randomUUID(), USER_ID, "Reviewer", OCCURRED, Map.of("lineCount", 2),
                    ControlAlertPriority.MEDIUM, null, null, REOPENED, 8L, "Counter", REQUEST.comment(), null),
            List.of(new ControlAlertService.HistoryView(ControlAlertStatus.CLOSED, ControlAlertStatus.NEW,
                    REQUEST.comment(), USER_ID, REOPENED, "Reviewer")), List.of());

    @Autowired private MockMvc mvc;
    @MockitoBean private ControlAlertService service;

    @ParameterizedTest
    @MethodSource("allowedAuthorities")
    void administratorOrGestionManagerCanReopenAndReceiveTheUpdatedDetail(String[] permissions) throws Exception {
        when(service.reopen(eq(ALERT_ID), eq(REQUEST), any(Authentication.class))).thenReturn(VIEW);

        mvc.perform(post(PATH).with(user("reviewer").authorities(authorities(permissions))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.id").value(ALERT_ID.toString()))
                .andExpect(jsonPath("$.alert.status").value("NEW"))
                .andExpect(jsonPath("$.alert.version").value(8))
                .andExpect(jsonPath("$.alert.occurredAt").value(OCCURRED.toString()))
                .andExpect(jsonPath("$.alert.updatedAt").value(REOPENED.toString()))
                .andExpect(jsonPath("$.alert.reviewComment").value(REQUEST.comment()))
                .andExpect(jsonPath("$.history[0].previousStatus").value("CLOSED"))
                .andExpect(jsonPath("$.history[0].newStatus").value("NEW"))
                .andExpect(jsonPath("$.history[0].changedByName").value("Reviewer"))
                .andExpect(jsonPath("$.workHistory").isEmpty());

        verify(service).reopen(eq(ALERT_ID), eq(REQUEST), argThat(auth -> auth.getName().equals("reviewer")));
    }

    @ParameterizedTest
    @MethodSource("deniedAuthorities")
    void insufficientPermissionsAreRejectedBeforeCallingTheService(String[] permissions) throws Exception {
        mvc.perform(post(PATH).with(user("reader").authorities(authorities(permissions))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeCallingTheService() throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("validOptionalComments")
    void commentIsOptionalAndAcceptsTheMaximumLength(String body, String comment) throws Exception {
        var expected = new ControlAlertService.TransitionRequest(7L, comment);
        when(service.reopen(eq(ALERT_ID), eq(expected), any(Authentication.class))).thenReturn(VIEW);

        mvc.perform(post(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(service).reopen(eq(ALERT_ID), eq(expected), any(Authentication.class));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void missingVersionOrAnOversizedCommentIsRejectedBeforeCallingTheService(String body) throws Exception {
        mvc.perform(post(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("serviceErrors")
    void returnsTheStandardConflictAndNotFoundResponses(RuntimeException failure, int statusCode, String code)
            throws Exception {
        when(service.reopen(eq(ALERT_ID), eq(REQUEST), any(Authentication.class))).thenThrow(failure);

        mvc.perform(post(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code));

        verify(service).reopen(eq(ALERT_ID), eq(REQUEST), any(Authentication.class));
    }

    private static SimpleGrantedAuthority[] authorities(String[] permissions) {
        return Arrays.stream(permissions).map(SimpleGrantedAuthority::new)
                .toArray(SimpleGrantedAuthority[]::new);
    }

    static Stream<Arguments> allowedAuthorities() {
        return Stream.of(
                Arguments.of((Object) new String[]{"ROLE_ADMIN"}),
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE"}));
    }

    static Stream<Arguments> deniedAuthorities() {
        return Stream.of(
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"}),
                Arguments.of((Object) new String[]{"CONTROL_ALERTS_MANAGE"}),
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS"}),
                Arguments.of((Object) new String[]{"CONTROL_ALERTS_READ"}),
                Arguments.of((Object) new String[]{"ROLE_USER"}));
    }

    static Stream<Arguments> validOptionalComments() {
        return Stream.of(
                Arguments.of("{\"version\":7}", null),
                Arguments.of("{\"version\":7,\"comment\":null}", null),
                Arguments.of(JSON.writeValueAsString(new ControlAlertService.TransitionRequest(7L, "")), ""),
                Arguments.of(JSON.writeValueAsString(new ControlAlertService.TransitionRequest(7L, "x".repeat(500))),
                        "x".repeat(500)));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("null"),
                Arguments.of("{}"),
                Arguments.of("{\"version\":null,\"comment\":\"Check again\"}"),
                Arguments.of(JSON.writeValueAsString(new ControlAlertService.TransitionRequest(7L, "x".repeat(501)))));
    }

    static Stream<Arguments> serviceErrors() {
        return Stream.of(
                Arguments.of(new IllegalStateException("Conflicto de version en la alerta"), 409, "STATE_CONFLICT"),
                Arguments.of(new NoSuchElementException("Alerta de control no encontrada"), 404, "NOT_FOUND"));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}

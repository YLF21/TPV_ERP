package com.tpverp.saas.marketing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoRequestFollowUpControllerTest {
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");
    private final DemoRequestFollowUpService service = mock(DemoRequestFollowUpService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestAdminController(
            mock(DemoRequestService.class), service)).setControllerAdvice(new DemoRequestExceptionHandler()).build();

    @Test
    void listsOnlyTheMinimalAssigneeRepresentationAndPreventsCaching() throws Exception {
        when(service.assignees()).thenReturn(List.of(new DemoRequestAssignee("operator")));
        mvc.perform(get("/api/v1/admin/demo-requests/assignees"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("[{\"username\":\"operator\"}]"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void readsInternalNotesWithTheirServerAuthorAndCreationTimeWithoutCaching() throws Exception {
        UUID noteId = UUID.randomUUID();
        when(service.detail(id)).thenReturn(new DemoRequestFollowUp(row(), List.of(
                new DemoRequestNote(noteId, "author", "A private follow-up note", now)), true));
        mvc.perform(get(route()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.request.id").value(id.toString()))
                .andExpect(jsonPath("$.request.followUpVersion").value(2))
                .andExpect(jsonPath("$.notes[0].id").value(noteId.toString()))
                .andExpect(jsonPath("$.notes[0].author").value("author"))
                .andExpect(jsonPath("$.notes[0].createdAt").value("2026-10-27T10:00:00Z"))
                .andExpect(jsonPath("$.hasMoreNotes").value(true));
    }

    @Test
    void bindsAnExplicitVersionAndInstantAndReturnsOnlyThePersistedResponse() throws Exception {
        when(service.update(eq(id), any())).thenReturn(new DemoRequestFollowUp(row(), List.of(), false));
        mvc.perform(put(route()).contentType(MediaType.APPLICATION_JSON).content("""
                {"assigneeUsername":"operator","nextAction":"Prepare demonstration",
                 "nextActionAt":"2026-11-02T15:30:00+01:00","note":"Confirmed interest","expectedVersion":1}
                """))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.request.followUpVersion").value(2));
        var payload = ArgumentCaptor.forClass(UpdateDemoRequestFollowUp.class);
        verify(service).update(eq(id), payload.capture());
        org.assertj.core.api.Assertions.assertThat(payload.getValue().nextActionAt())
                .isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
        org.assertj.core.api.Assertions.assertThat(payload.getValue().expectedVersion()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(payload.getValue().note()).isEqualTo("Confirmed interest");
    }

    @Test
    void rejectsAbsentNegativeOrOverlongPayloadsBeforeReachingTheService() throws Exception {
        for (String payload : List.of("{}", "{\"expectedVersion\":-1}",
                "{\"expectedVersion\":0,\"nextAction\":\"" + "x".repeat(501) + "\"}",
                "{\"expectedVersion\":0,\"assigneeUsername\":\"" + "x".repeat(81) + "\"}",
                "{\"expectedVersion\":0,\"note\":\"" + "x".repeat(2001) + "\"}")) {
            mvc.perform(put(route()).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void aConcurrentEditReturnsAConflictInsteadOfPretendingToHaveSaved() throws Exception {
        when(service.update(eq(id), any())).thenThrow(new DemoRequestFailure(HttpStatus.CONFLICT,
                "FOLLOW_UP_CONFLICT", "El seguimiento ha cambiado"));
        mvc.perform(put(route()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("FOLLOW_UP_CONFLICT"));
    }

    private String route() { return "/api/v1/admin/demo-requests/" + id + "/follow-up"; }

    private DemoRequestView row() {
        return new DemoRequestView(id, DemoProduct.APP_VENTA, List.of(DemoProduct.APP_VENTA), "Synthetic contact",
                "Synthetic company", "synthetic@example.com", null, null, "es", null, null, null, null, null,
                "CONTACTED", now, now, "operator", "Prepare demonstration", now.plusSeconds(3600), 2);
    }
}

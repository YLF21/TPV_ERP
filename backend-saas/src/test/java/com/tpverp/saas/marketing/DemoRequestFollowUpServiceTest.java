package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.saas.admin.AdminAuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class DemoRequestFollowUpServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T10:15:30.123456789Z");
    private final UUID id = UUID.randomUUID();
    private final DemoRequestRepository requests = mock(DemoRequestRepository.class);
    private final DemoRequestFollowUpRepository repository = mock(DemoRequestFollowUpRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final DemoRequestFollowUpService service = new DemoRequestFollowUpService(
            requests, repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void persistsAssignmentAndActionBeforeAppendingTheServerAuthoredNoteAndReturnsSavedState() {
        UUID assignee = UUID.randomUUID();
        DemoRequestView saved = row(1, "Operator", "Review the catalogue", NOW.plusSeconds(3600));
        when(requests.findById(id)).thenReturn(Optional.of(row(0, null, null, null)), Optional.of(saved));
        when(repository.eligibleAssignee("operator")).thenReturn(Optional.of(assignee));
        when(repository.update(id, assignee, "Review the catalogue", NOW.plusSeconds(3600), 0)).thenReturn(true);
        when(audit.currentUsername()).thenReturn("authenticated-agent");
        when(repository.notes(id, 101)).thenReturn(List.of());

        DemoRequestFollowUp result = service.update(id, new UpdateDemoRequestFollowUp(
                " operator ", " Review the catalogue ", NOW.plusSeconds(3600), " Customer asked for a stock demo ", 0L));

        assertThat(result.request()).isEqualTo(saved);
        var note = ArgumentCaptor.forClass(DemoRequestNote.class);
        var order = inOrder(repository, audit);
        order.verify(repository).update(id, assignee, "Review the catalogue", NOW.plusSeconds(3600), 0);
        order.verify(audit).currentUsername();
        order.verify(repository).appendNote(eq(id), note.capture());
        assertThat(note.getValue().author()).isEqualTo("authenticated-agent");
        assertThat(note.getValue().body()).isEqualTo("Customer asked for a stock demo");
        assertThat(note.getValue().createdAt()).isEqualTo(Instant.parse("2026-10-27T10:15:30.123456Z"));
        assertThat(note.getValue().id()).isNotNull();
        verify(audit).log("UPDATE_DEMO_REQUEST_FOLLOW_UP", "MARKETING_DEMO_REQUEST", id.toString(),
                "FOLLOW_UP_UPDATED_WITH_NOTE");
    }

    @Test
    void allowsClearingAssignmentAndActionWithoutAddingAnEmptyNote() {
        when(requests.findById(id)).thenReturn(Optional.of(row(0, "operator", "Previous action", NOW)));
        when(repository.update(id, null, null, null, 0)).thenReturn(true);
        when(repository.notes(id, 101)).thenReturn(List.of());
        service.update(id, new UpdateDemoRequestFollowUp(" ", " ", null, " \n ", 0L));
        verify(repository).update(id, null, null, null, 0);
        verify(repository, never()).eligibleAssignee(anyString());
        verify(repository, never()).appendNote(any(), any());
        verify(audit).log("UPDATE_DEMO_REQUEST_FOLLOW_UP", "MARKETING_DEMO_REQUEST", id.toString(), "FOLLOW_UP_UPDATED");
    }

    @Test
    void permitsANextActionWithoutInventingADueDate() {
        when(requests.findById(id)).thenReturn(Optional.of(row(0, null, null, null)));
        when(repository.update(id, null, "Ask about devices", null, 0)).thenReturn(true);
        when(repository.notes(id, 101)).thenReturn(List.of());
        service.update(id, new UpdateDemoRequestFollowUp(null, "Ask about devices", null, null, 0L));
        verify(repository).update(id, null, "Ask about devices", null, 0);
    }

    @Test
    void rejectsAStaleVersionBeforeAppendingOrAuditingAnything() {
        when(requests.findById(id)).thenReturn(Optional.of(row(4, null, null, null)));
        assertFailure(new UpdateDemoRequestFollowUp("operator", "Action", null, "Note", 3L),
                HttpStatus.CONFLICT, "FOLLOW_UP_CONFLICT");
        verifyNoInteractions(repository, audit);
    }

    @Test
    void aRaceLostDuringTheSqlCompareAndSetDoesNotAppendTheNote() {
        when(requests.findById(id)).thenReturn(Optional.of(row(0, null, null, null)));
        when(repository.update(id, null, "Action", null, 0)).thenReturn(false);
        assertFailure(new UpdateDemoRequestFollowUp(null, "Action", null, "Do not duplicate this note", 0L),
                HttpStatus.CONFLICT, "FOLLOW_UP_CONFLICT");
        verify(repository, never()).appendNote(any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void refusesAnInactiveMissingOrIneligibleAssigneeWithoutChangingTheRequest() {
        when(requests.findById(id)).thenReturn(Optional.of(row(0, null, null, null)));
        when(repository.eligibleAssignee("not-eligible")).thenReturn(Optional.empty());
        assertFailure(new UpdateDemoRequestFollowUp("not-eligible", "Action", null, "Note", 0L),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        verify(repository, never()).update(any(), any(), any(), any(), anyLong());
        verify(repository, never()).appendNote(any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void validatesFieldLimitsAndTheDateActionPairAtTheServiceBoundary() {
        when(requests.findById(id)).thenReturn(Optional.of(row(0, null, null, null)));
        for (UpdateDemoRequestFollowUp change : List.of(
                new UpdateDemoRequestFollowUp("x".repeat(81), null, null, null, 0L),
                new UpdateDemoRequestFollowUp(null, "x".repeat(501), null, null, 0L),
                new UpdateDemoRequestFollowUp(null, null, null, "x".repeat(2001), 0L),
                new UpdateDemoRequestFollowUp(null, " ", NOW, null, 0L),
                new UpdateDemoRequestFollowUp(null, null, null, null, -1L),
                new UpdateDemoRequestFollowUp(null, null, null, null, null))) {
            assertFailure(change, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        verifyNoInteractions(repository, audit);
    }

    @Test
    void missingRequestsRemainNotFoundRatherThanCreatingOrphanNotes() {
        when(requests.findById(id)).thenReturn(Optional.empty());
        assertFailure(new UpdateDemoRequestFollowUp(null, null, null, "Note", 0L), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThatThrownBy(() -> service.detail(id)).isInstanceOfSatisfying(DemoRequestFailure.class,
                failure -> assertThat(failure.status).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(repository, audit);
    }

    @Test
    void readsTheMostRecentHundredNotesAndSignalsThatOlderHistoryStillExists() {
        when(requests.findById(id)).thenReturn(Optional.of(row(7, "operator", "Action", NOW)));
        var notes = IntStream.range(0, 101).mapToObj(index -> new DemoRequestNote(UUID.randomUUID(),
                "author", "Note " + index, NOW.minusSeconds(index))).toList();
        when(repository.notes(id, 101)).thenReturn(notes);
        DemoRequestFollowUp detail = service.detail(id);
        assertThat(detail.notes()).containsExactlyElementsOf(notes.subList(0, 100));
        assertThat(detail.hasMoreNotes()).isTrue();
        assertThat(detail.request().followUpVersion()).isEqualTo(7);
        verify(repository, never()).appendNote(any(), any());
        verify(repository, never()).update(any(), any(), any(), any(), anyLong());
        verifyNoInteractions(audit);
    }

    private void assertFailure(UpdateDemoRequestFollowUp change, HttpStatus status, String code) {
        assertThatThrownBy(() -> service.update(id, change)).isInstanceOfSatisfying(DemoRequestFailure.class, failure -> {
            assertThat(failure.status).isEqualTo(status);
            assertThat(failure.code).isEqualTo(code);
            assertThat(failure.getMessage()).doesNotContain("Do not duplicate", "not-eligible");
        });
    }

    private DemoRequestView row(long version, String assignee, String action, Instant dueAt) {
        return new DemoRequestView(id, DemoProduct.APP_VENTA, List.of(DemoProduct.APP_VENTA), "Synthetic contact",
                "Synthetic company", "synthetic@example.com", null, null, "es", null, null, null, null, null,
                "NEW", NOW, NOW, assignee, action, dueAt, version);
    }
}

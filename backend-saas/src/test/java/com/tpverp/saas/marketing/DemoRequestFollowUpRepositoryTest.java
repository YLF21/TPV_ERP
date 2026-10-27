package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DemoRequestFollowUpRepositoryTest {
    @Test
    void theSqlCompareAndSetIncrementsOnlyFollowUpAndBindsTheExpectedVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID request = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Instant whenDue = Instant.parse("2026-11-02T10:00:00Z");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var repository = new DemoRequestFollowUpRepository(jdbc);
        assertThat(repository.update(request, user, "Discuss devices", whenDue, 7)).isTrue();
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(user), eq("Discuss devices"), eq(Timestamp.from(whenDue)), eq(request), eq(7L));
        assertThat(sql.getValue()).contains("follow_up_version = follow_up_version + 1")
                .contains("where id = ? and follow_up_version = ?")
                .doesNotContain("status =", "message =", "contact_name =");
    }

    @Test
    void noteTextIsBoundAsDataAndTheQueryHasStableNewestFirstOrderingAndALimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var repository = new DemoRequestFollowUpRepository(jdbc);
        UUID request = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-27T10:00:00Z");
        var note = new DemoRequestNote(UUID.randomUUID(), "operator", "' ; DROP TABLE --", createdAt);
        repository.appendNote(request, note);
        var insert = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(insert.capture(), eq(note.id()), eq(request), eq("operator"), eq(note.body()), eq(Timestamp.from(createdAt)));
        assertThat(insert.getValue()).contains("insert into saas_marketing_demo_note").doesNotContain(note.body());
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<DemoRequestNote>>any(), eq(request), eq(101)))
                .thenReturn(List.of(note));
        assertThat(repository.notes(request, 101)).containsExactly(note);
        var select = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(select.capture(), org.mockito.ArgumentMatchers.<RowMapper<DemoRequestNote>>any(), eq(request), eq(101));
        assertThat(select.getValue()).contains("where request_id = ?")
                .contains("order by created_at desc, id desc limit ?");
    }

    @Test
    void assigneeLookupUsesActiveOperationRolesAndNeverReadsPasswordHashes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var repository = new DemoRequestFollowUpRepository(jdbc);
        repository.assignees();
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<DemoRequestAssignee>>any());
        assertThat(sql.getValue()).contains("select u.username").contains("u.active = true")
                .contains("rp.permission_code = 'MANAGE_OPERATIONS'").doesNotContain("password_hash");
    }
}

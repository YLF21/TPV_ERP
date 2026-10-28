package com.tpverp.saas.marketing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DemoRequestFollowUpRepository {
    private final JdbcTemplate jdbc;

    public DemoRequestFollowUpRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DemoRequestAssignee> assignees() {
        return jdbc.query("""
                select u.username from saas_admin_user u
                where u.active = true and exists (
                    select 1 from saas_admin_user_role ur
                    join saas_admin_role_permission rp on rp.role_id = ur.role_id
                    where ur.user_id = u.id and rp.permission_code = 'MANAGE_OPERATIONS'
                ) order by lower(u.username), u.username
                """, (rs, index) -> new DemoRequestAssignee(rs.getString("username")));
    }

    public Optional<UUID> eligibleAssignee(String username) {
        return jdbc.query("""
                select u.id from saas_admin_user u
                where lower(u.username) = lower(?) and u.active = true and exists (
                    select 1 from saas_admin_user_role ur
                    join saas_admin_role_permission rp on rp.role_id = ur.role_id
                    where ur.user_id = u.id and rp.permission_code = 'MANAGE_OPERATIONS'
                )
                """, (rs, index) -> rs.getObject("id", UUID.class), username).stream().findFirst();
    }

    public boolean update(UUID id, UUID assigneeId, String nextAction, Instant nextActionAt, long expectedVersion) {
        return jdbc.update("""
                update saas_marketing_demo_request
                set assignee_user_id = ?, next_action = ?, next_action_at = ?,
                    follow_up_version = follow_up_version + 1
                where id = ? and follow_up_version = ?
                """, assigneeId, nextAction, nextActionAt == null ? null : Timestamp.from(nextActionAt),
                id, expectedVersion) == 1;
    }

    public void appendNote(UUID requestId, DemoRequestNote note) {
        jdbc.update("""
                insert into saas_marketing_demo_note(id, request_id, author, body, created_at)
                values (?, ?, ?, ?, ?)
                """, note.id(), requestId, note.author(), note.body(), Timestamp.from(note.createdAt()));
    }

    public List<DemoRequestNote> notes(UUID requestId, int limit) {
        return jdbc.query("""
                select id, author, body, created_at from saas_marketing_demo_note
                where request_id = ? order by created_at desc, id desc limit ?
                """, (rs, index) -> new DemoRequestNote(rs.getObject("id", UUID.class),
                rs.getString("author"), rs.getString("body"), rs.getTimestamp("created_at").toInstant()),
                requestId, limit);
    }
}

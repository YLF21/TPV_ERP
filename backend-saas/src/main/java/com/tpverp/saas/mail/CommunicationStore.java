package com.tpverp.saas.mail;

import static com.tpverp.saas.mail.Communications.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class CommunicationStore {
    private static final String COUNTS = """
            left join lateral (select
                count(*) filter (where status='PENDING') pending, count(*) filter (where status='SENDING') sending,
                count(*) filter (where status='SENT') sent, count(*) filter (where status='FAILED') failed,
                count(*) filter (where status='UNCERTAIN') uncertain, count(*) filter (where status='CANCELLED') cancelled,
                count(*) filter (where attempts>0) attempted
                from saas_communication_recipient where communication_id=c.id
            ) totals on true
            """;
    private static final String SUMMARY = "c.id,c.subject,c.version,c.created_by,c.created_at,c.updated_at,c.queued_at,c.cancelled_at";
    private static final String LEGACY_SELECTION = """
            from unnest(%s) with ordinality selected(id,position)
            left join lateral (select directory_id from saas_communication_address_source s
                where s.kind='CLIENT_USER' and s.origin_id=selected.id limit 1) legacy on true
            """;
    private final JdbcTemplate jdbc;
    CommunicationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<Summary> history(int page, int size) {
        long offset = offset(page, size);
        long total = jdbc.queryForObject("select count(*) from saas_communication",Long.class);
        var items = jdbc.query("select c.*,totals.* from (select " + SUMMARY + """
                 from saas_communication c order by updated_at desc,id limit ? offset ?) c
                """ + COUNTS + " order by c.updated_at desc,c.id", (rs, i) -> summary(rs), size, offset);
        return new Page<>(items, total);
    }

    Detail detail(UUID id) {
        // Editing an older draft must check the same mailbox in the current directory.
        // Its stored user IDs still govern review until the operator saves a new selection.
        String displayIds = """
                case when c.queued_at is not null then c.recipient_ids else array(
                    select coalesce(legacy.directory_id,selected.id)
                    %s
                    group by coalesce(legacy.directory_id,selected.id) order by min(position)
                ) end display_ids
                """.formatted(LEGACY_SELECTION.formatted("c.recipient_ids"));
        return jdbc.query("select c.*,totals.*," + displayIds + " from saas_communication c " + COUNTS + " where c.id=?",
                (rs,i) -> new Detail(summary(rs),rs.getString("html"),rs.getString("plain_text"),
                        Mode.valueOf(rs.getString("audience_mode")),ids(rs,"display_ids"),rs.getObject("sender_account_id",UUID.class),
                        rs.getString("from_address"),rs.getString("from_name"),rs.getString("reply_to")),id)
                .stream().findFirst().orElseThrow(Communications::missing);
    }

    // A checked legacy user remains that user when saving a subject, body, or other recipient.
    // Otherwise a new owner at the same address could silently widen an existing selection.
    List<UUID> retainLegacySelections(List<UUID> saved, List<UUID> requested) {
        if (saved.isEmpty() || requested.isEmpty()) return requested;
        var aliases = new HashMap<UUID,List<UUID>>();
        jdbc.query("select selected.id,legacy.directory_id " + LEGACY_SELECTION.formatted("?::uuid[]")
                        + " where legacy.directory_id is not null and selected.id<>legacy.directory_id order by position",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> aliases.computeIfAbsent(
                        rs.getObject("directory_id",UUID.class), ignored -> new ArrayList<>()).add(rs.getObject("id",UUID.class)),
                (Object)saved.toArray(UUID[]::new));
        if (aliases.isEmpty()) return requested;
        var result = new LinkedHashSet<UUID>();
        var originallySelected = Set.copyOf(saved);
        for (UUID id : requested) {
            if (originallySelected.contains(id)) result.add(id);
            result.addAll(aliases.getOrDefault(id,List.of(id)));
        }
        if (result.size() > 10000) throw invalid();
        return List.copyOf(result);
    }

    record Locked(long version, UUID reviewId, long reviewedCount, boolean queued, boolean cancelled,
                  String subject, String html, String text, Mode mode, List<UUID> ids, Long queuedVersion, Long smtpVersion, UUID senderAccountId) {}
    Locked lock(UUID id) {
        return jdbc.query("select * from saas_communication where id=? for update", (rs,i) ->
                new Locked(rs.getLong("version"),rs.getObject("review_id",UUID.class),rs.getLong("reviewed_count"),
                        rs.getTimestamp("queued_at") != null,rs.getTimestamp("cancelled_at") != null,
                        rs.getString("subject"),rs.getString("html"),rs.getString("plain_text"),
                        Mode.valueOf(rs.getString("audience_mode")),ids(rs,"recipient_ids"),rs.getObject("queued_version",Long.class),
                        rs.getObject("smtp_version",Long.class),rs.getObject("sender_account_id",UUID.class)),id)
                .stream().findFirst().orElseThrow(Communications::missing);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<Recipient> recipients(UUID id, UUID reviewId, int page, int size) {
        long offset = offset(page, size);
        var state = jdbc.query("select review_id,queued_at from saas_communication where id=?", (rs,i) ->
                new Snapshot(rs.getObject("review_id",UUID.class), rs.getTimestamp("queued_at") != null),id)
                .stream().findFirst().orElseThrow(Communications::missing);
        if (reviewId != null && !reviewId.equals(state.reviewId())) throw staleReview();
        if (reviewId == null && !state.queued()) return new Page<>(List.of(),0);
        String from = " from saas_communication_recipient where communication_id=?";
        var items = jdbc.query("select *" + from + " order by lower(label),id limit ? offset ?", (rs,i) ->
                new Recipient(rs.getObject("id",UUID.class),rs.getString("label"),rs.getString("email"),
                        rs.getString("status"),rs.getInt("attempts"),rs.getString("last_error"),
                        instant(rs.getTimestamp("sent_at"))),id,size,offset);
        return new Page<>(items,jdbc.queryForObject("select count(*)" + from,Long.class,id));
    }

    private record Snapshot(UUID reviewId, boolean queued) {}
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static List<UUID> ids(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        try { return List.of((UUID[]) array.getArray()); }
        finally { array.free(); }
    }
    private static Summary summary(ResultSet rs) throws SQLException {
        var counts = new Counts(rs.getLong("pending"),rs.getLong("sending"),rs.getLong("sent"),
                rs.getLong("failed"),rs.getLong("uncertain"),rs.getLong("cancelled"));
        String status;
        if (rs.getTimestamp("queued_at") == null) status = "DRAFT";
        else if (rs.getTimestamp("cancelled_at") != null) status = "CANCELLED";
        else if (counts.pending() + counts.sending() == 0) status = "COMPLETED";
        else if (rs.getLong("attempted") > 0) status = "IN_PROGRESS";
        else status = "QUEUED";
        return new Summary(rs.getObject("id",UUID.class),rs.getString("subject"),status,rs.getLong("version"),
                instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at")),rs.getString("created_by"),counts);
    }
}

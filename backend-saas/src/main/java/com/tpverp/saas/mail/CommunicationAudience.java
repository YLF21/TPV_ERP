package com.tpverp.saas.mail;

import static com.tpverp.saas.mail.Communications.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
class CommunicationAudience {
    // The frozen label is indexed. Bound its UTF-8 size even for a widely shared address.
    private static final String MAILBOX = """
            directory_id id, array_to_string((array_agg(distinct left(label,160) order by left(label,160)))[1:3], ' / ')
                || case when count(distinct label)>3 then ' …' else '' end label,
            (array_agg(email order by active desc,email collate "C"))[1] email, bool_or(active) active
            """;
    private static final String DIRECTORY = """
             from saas_communication_address_source s
            """;
    private static final String GROUP = " group by directory_id,lower(email) ";
    private static final String SEARCH = " having bool_or(strpos(lower(concat_ws(' ',label,username,email,company_name)),lower(?)) > 0) ";
    private static final String COMPANY_NAMES = "array_remove(array_agg(distinct company_name order by company_name),null) companies";
    private static final String KINDS = "array_agg(distinct kind order by kind) kinds";
    // Existing envelopes predate reviewed_sources and can only use their original tenant identity.
    private static final String REVIEWED = """
            jsonb_to_recordset(coalesce(e.reviewed_sources,jsonb_build_array(jsonb_build_object(
                'kind','CLIENT_USER','origin',e.source_id,'member',e.source_id)))) reviewed(kind text,origin uuid,member uuid)
            """;
    private static final String SAME_SOURCE = """
            s.kind=reviewed.kind and s.origin_id=reviewed.origin and s.member_id=reviewed.member
            and s.active and lower(s.email)=lower(e.email)
            """;
    private final JdbcTemplate jdbc;
    CommunicationAudience(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<AudienceEntry> page(int page, int size, String q) {
        long offset = offset(page, size);
        if (q.length() > 160 || q.indexOf(0) >= 0) throw invalid();
        String filter = q.strip();
        String groups = DIRECTORY + GROUP + SEARCH;
        long total = jdbc.queryForObject("select count(*) from (select 1" + groups + ") matches", Long.class, filter);
        String sql = "select * from (select " + MAILBOX + "," + COMPANY_NAMES + "," + KINDS + groups
                + ") matches order by lower(label),id limit ? offset ?";
        var items = jdbc.query(sql, (rs, i) -> {
            String email = rs.getString("email");
            String reason = reason(rs.getBoolean("active"), email);
            return new AudienceEntry(rs.getObject("id",UUID.class),rs.getString("label"),email,reason == null,reason,
                    strings(rs,"companies"),strings(rs,"kinds"));
        }, filter, size, offset);
        return new Page<>(items, total);
    }

    record Totals(long included, long omitted, long duplicate) {}
    private record Envelope(UUID id, String label, String email, String reviewedSources) {}

    Totals snapshot(UUID communicationId, Mode mode, List<UUID> ids) {
        // A new selection denotes a mailbox. A legacy tenant UUID selects that original user only.
        String selection = mode == Mode.ALL ? "" : """
                cross join lateral (select distinct candidate from unnest(array[directory_id,
                    case when kind='CLIENT_USER' then member_id end]) candidate where candidate=any(?)) picked
                """;
        String counted = mode == Mode.ALL ? "member_id" : "picked.candidate";
        String sql = "select " + MAILBOX + ",count(distinct " + counted + ") seen,count(distinct " + counted
                + ") filter(where active) active_count," + """
                jsonb_agg(distinct jsonb_build_object('kind',kind,'origin',origin_id,'member',member_id))
                    filter(where active) reviewed_sources
                """ + DIRECTORY + selection + GROUP;
        return jdbc.query(connection -> {
            var stmt = connection.prepareStatement(sql);
            stmt.setFetchSize(256);
            if (mode == Mode.SELECTED) stmt.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            return stmt;
        }, rs -> {
            long seen = 0, valid = 0, inserted = 0;
            var batch = new ArrayList<Envelope>(256);
            while (rs.next()) {
                seen += rs.getLong("seen");
                String email = rs.getString("email");
                if (reason(rs.getBoolean("active"),email) != null) continue;
                valid += rs.getLong("active_count");
                batch.add(new Envelope(rs.getObject("id",UUID.class),rs.getString("label"),email,rs.getString("reviewed_sources")));
                if (batch.size() == 256) {
                    inserted += insert(communicationId, batch);
                    batch.clear();
                }
            }
            inserted += insert(communicationId, batch);
            long expected = mode == Mode.SELECTED ? ids.size() : seen;
            return new Totals(inserted, expected-valid, valid-inserted);
        });
    }

    private int insert(UUID communicationId, List<Envelope> batch) {
        if (batch.isEmpty()) return 0;
        return jdbc.update("""
                insert into saas_communication_recipient(id,communication_id,source_id,label,email,status,reviewed_sources)
                select gen_random_uuid(),?,s.id,s.label,s.email,'REVIEW',s.sources::jsonb
                from unnest(?::uuid[],?::text[],?::text[],?::text[]) s(id,label,email,sources)
                on conflict (communication_id,lower(email)) do nothing
                """, communicationId, batch.stream().map(Envelope::id).toArray(UUID[]::new),
                batch.stream().map(Envelope::label).toArray(String[]::new), batch.stream().map(Envelope::email).toArray(String[]::new),
                batch.stream().map(Envelope::reviewedSources).toArray(String[]::new));
    }

    boolean stillEligible(UUID communicationId, UUID envelopeId) {
        String sql = "select not exists(select 1 from saas_communication_recipient e where e.communication_id=? "
                + (envelopeId == null ? "" : "and e.id=? ")
                + "and not exists(select 1 from " + REVIEWED + " join saas_communication_address_source s on " + SAME_SOURCE + "))";
        Object[] args = envelopeId == null ? new Object[]{communicationId} : new Object[]{communicationId,envelopeId};
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));
    }

    private record Source(String kind, UUID origin, UUID member) {}
    boolean lockEligibleRecipient(UUID envelopeId) {
        var sources = jdbc.query("select reviewed.* from saas_communication_recipient e cross join lateral " + REVIEWED
                + " where e.id=? order by reviewed.origin,reviewed.kind,reviewed.member",
                (rs,i) -> new Source(rs.getString("kind"),rs.getObject("origin",UUID.class),rs.getObject("member",UUID.class)),envelopeId);
        for (var source : sources) {
            // Lock the physical rows, then read the view in a fresh READ COMMITTED statement.
            // This also covers an owner removed from the JSON array while the worker waited.
            String lock = switch (source.kind()) {
                case "CLIENT_USER" -> """
                        select u.id from saas_tenant_user u join saas_client_access_request r on r.tenant_user_id=u.id
                        where u.id=? and u.active and r.status='APPROVED' for share of u,r
                        """;
                case "COMPANY_OWNER" -> "select id from saas_company where id=? for share";
                case "COMPANY_CONTACT" -> """
                        with company as materialized (select id from saas_company where id=? for share)
                        select o.company_id from company join saas_company_operations o on o.company_id=company.id for share of o
                        """;
                default -> throw new IllegalStateException("Origen de comunicación desconocido");
            };
            if (jdbc.query(lock,(rs,i) -> rs.getObject(1,UUID.class),source.origin()).isEmpty()) continue;
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (select 1 from saas_communication_recipient e join saas_communication_address_source s
                        on s.kind=? and s.origin_id=? and s.member_id=? and s.active and lower(s.email)=lower(e.email)
                        where e.id=?)
                    """,Boolean.class,source.kind(),source.origin(),source.member(),envelopeId))) return true;
        }
        return false;
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        try { return List.of((String[]) array.getArray()); }
        finally { array.free(); }
    }
    private static String reason(boolean active, String email) {
        if (!active) return "INACTIVE";
        if (email == null) return "MISSING_EMAIL";
        if (email.length() > 254) return "INVALID_EMAIL";
        try { SmtpSettings.validateAddress(email); }
        catch (ResponseStatusException invalid) { return "INVALID_EMAIL"; }
        return null;
    }
}

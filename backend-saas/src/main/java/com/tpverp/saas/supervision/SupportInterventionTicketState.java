package com.tpverp.saas.supervision;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The caller must hold the support ticket row lock. */
@Component
public class SupportInterventionTicketState {
    private final JdbcTemplate jdbc;
    public SupportInterventionTicketState(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void changed(UUID ticketId, String previousStatus, String nextStatus) {
        if (previousStatus.equals(nextStatus)) return;
        String override = "RESUELTO".equals(nextStatus) ? "RESOLVED"
                : "RESUELTO".equals(previousStatus) ? "REMOTE_PENDING" : null;
        jdbc.update("""
                insert into saas_support_intervention(ticket_id,status,version)
                select t.id,coalesce(?,'REMOTE_PENDING'),1 from saas_support_ticket t
                 where t.id=? and exists(select 1 from saas_store_failure_manual m
                    where m.ticket_id=t.id and m.company_id=t.company_id)
                on conflict(ticket_id) do update
                set status=coalesce(?,saas_support_intervention.status),version=saas_support_intervention.version+1
                """, override, ticketId, override);
    }
}
package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentRecoveryApi.*;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommercialDocumentRecoveryService {
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final NamedParameterJdbcTemplate jdbc;

    public CommercialDocumentRecoveryService(SaasInstallationRepository installations,
            InstallationAuthenticator authenticator, NamedParameterJdbcTemplate jdbc) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.jdbc = jdbc;
    }

    /** Header, event and revision ledger are inspected in one snapshot; this never schedules a write. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Response status(Request request, String token) {
        if (request == null) throw invalid();
        var installation = authenticator.requireLinkedInstallation(request.companyId(), request.storeId(),
                installations.findByCompany_IdAndStore_Id(request.companyId(), request.storeId()), token);
        UUID companyId = installation.getCompany().getId();
        UUID storeId = installation.getStore().getId();
        UUID installationId = installation.getId();
        var parameters = new MapSqlParameterSource().addValue("companyId", companyId)
                .addValue("storeId", storeId).addValue("installationId", installationId);
        var values = new StringJoiner(", ");
        for (int index = 0; index < request.documents().size(); index++) {
            var document = request.documents().get(index);
            values.add("(cast(:document" + index + " as uuid), cast(:event" + index
                    + " as uuid), cast(:revision" + index + " as bigint), " + index + ")");
            parameters.addValue("document" + index, document.documentId())
                    .addValue("event" + index, document.eventId()).addValue("revision" + index, document.sourceRevision());
        }
        var documents = jdbc.query("""
                with requested(document_id, event_id, source_revision, ordinal) as (values %s)
                select q.document_id, q.event_id is not null as event_requested,
                       d.source_document_id is not null as document_present,
                       d.source_installation_id = :installationId as owned,
                       case when d.source_installation_id = :installationId then d.source_revision end as current_revision,
                       case when d.source_installation_id = :installationId then d.source_event_id end as current_event_id,
                       case when d.source_installation_id = :installationId then d.total end as total,
                       case when d.source_installation_id = :installationId then d.currency end as currency,
                       case when d.source_installation_id = :installationId and d.customer_local_id is not null then
                           exists(select 1 from saas_customer_identity_link l
                             join saas_erp_customer c on c.id = l.customer_id and c.company_id = l.company_id
                            where l.company_id = d.company_id and l.installation_id = d.source_installation_id
                              and l.local_customer_id = d.customer_local_id) end as customer_linked,
                       e.projection_status as requested_event_status,
                       coalesce(d.source_installation_id = :installationId
                         and e.projection_status = 'PROJECTED' and e.schema_version = 2
                         and e.operation in ('CONFIRMAR', 'ACTUALIZAR', 'ANULAR')
                         and (e.payload::jsonb -> 'schemaVersion') = '2'::jsonb
                         and (e.payload::jsonb -> 'sourceRevision') = to_jsonb(q.source_revision)
                         and r.source_payload_hash = e.payload_hash and ledger_event.event_id is not null, false)
                           as revision_recorded
                  from requested q
                  left join saas_commercial_document d
                    on d.company_id = :companyId and d.store_id = :storeId and d.source_document_id = q.document_id
                  left join saas_sync_event e
                    on e.event_id = q.event_id and e.company_id = :companyId and e.store_id = :storeId
                   and e.installation_id = :installationId and e.entity_id = q.document_id and e.entity_type = 'DOCUMENTO'
                  left join saas_commercial_document_revision r
                    on r.company_id = :companyId and r.store_id = :storeId
                   and r.source_document_id = q.document_id and r.source_revision = q.source_revision
                  left join saas_sync_event ledger_event
                    on ledger_event.event_id = r.source_event_id and ledger_event.company_id = :companyId
                   and ledger_event.store_id = :storeId and ledger_event.installation_id = :installationId
                   and ledger_event.entity_id = q.document_id and ledger_event.entity_type = 'DOCUMENTO'
                   and ledger_event.projection_status = 'PROJECTED' and ledger_event.schema_version = 2
                   and ledger_event.operation in ('CONFIRMAR', 'ACTUALIZAR', 'ANULAR')
                   and ledger_event.payload_hash = r.source_payload_hash
                   and (ledger_event.payload::jsonb -> 'schemaVersion') = '2'::jsonb
                   and (ledger_event.payload::jsonb -> 'sourceRevision') = to_jsonb(q.source_revision)
                 order by q.ordinal
                """.formatted(values), parameters, CommercialDocumentRecoveryService::map);
        return new Response(companyId, storeId, installationId, CommercialDocumentSnapshot.SCHEMA_VERSION, documents);
    }

    private static DocumentStatus map(ResultSet row, int index) throws SQLException {
        UUID documentId = row.getObject("document_id", UUID.class);
        boolean requested = row.getBoolean("event_requested");
        if (row.getBoolean("document_present") && !row.getBoolean("owned")) {
            // Even a caller-supplied event ID cannot expose the former installation's header or customer.
            return new DocumentStatus(documentId, Status.OTHER_INSTALLATION, null, null,
                    requested ? EventStatus.MISSING : EventStatus.NOT_REQUESTED, false, null, null, null);
        }
        String event = row.getString("requested_event_status");
        EventStatus eventStatus = !requested ? EventStatus.NOT_REQUESTED : event == null ? EventStatus.MISSING : EventStatus.valueOf(event);
        var total = row.getBigDecimal("total");
        return new DocumentStatus(documentId, row.getBoolean("document_present") ? Status.PROJECTED : Status.MISSING,
                row.getObject("current_revision", Long.class), row.getObject("current_event_id", UUID.class),
                eventStatus, row.getBoolean("revision_recorded"), row.getObject("customer_linked", Boolean.class),
                total == null ? null : total.toPlainString(), row.getString("currency"));
    }
}

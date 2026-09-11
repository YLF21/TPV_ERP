package com.tpverp.saas.document;

import com.tpverp.saas.sync.SaasSyncEvent;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class CommercialDocumentProjectionRepository {

    private final JdbcTemplate jdbc;

    public CommercialDocumentProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void project(SaasSyncEvent event, CommercialDocumentSnapshot snapshot,
                 CommercialDocumentQueryMetadata metadata) {
        UUID companyId = event.getCompany().getId();
        UUID storeId = event.getStore().getId();
        UUID documentId = event.getEntityId();
        UUID installationId = event.getInstallation().getId();
        String lockKey = "saas_commercial_document:" + companyId + ":" + storeId + ":" + documentId;
        // The same transaction lock serializes the first INSERT and later revisions of this document.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", Object.class, lockKey);
        List<Current> rows = jdbc.query("""
                select source_installation_id, source_revision, source_payload_hash
                  from saas_commercial_document
                 where company_id = ? and store_id = ? and source_document_id = ?
                """, (rs, index) -> new Current(rs.getObject("source_installation_id", UUID.class),
                        rs.getLong("source_revision"), rs.getString("source_payload_hash")),
                companyId, storeId, documentId);
        Current current = rows.isEmpty() ? null : rows.getFirst();
        if (current != null && !current.installationId().equals(installationId)) {
            throw conflict("La instalacion de origen del documento no coincide");
        }
        List<String> knownHashes = jdbc.queryForList("""
                select source_payload_hash from saas_commercial_document_revision
                 where company_id = ? and store_id = ? and source_document_id = ? and source_revision = ?
                """, String.class, companyId, storeId, documentId, snapshot.sourceRevision());
        if (!knownHashes.isEmpty()) {
            requireSameHash(knownHashes.getFirst(), event.getPayloadHash());
            return;
        }
        if (current != null && snapshot.sourceRevision() <= current.revision()) {
            if (snapshot.sourceRevision() == current.revision()) {
                requireSameHash(current.payloadHash(), event.getPayloadHash());
            }
            rememberRevision(event, snapshot);
            return;
        }
        jdbc.update("""
                insert into saas_commercial_document (
                    company_id, store_id, source_document_id, source_installation_id,
                    source_event_id, source_payload_hash, source_revision, schema_version,
                    document_type, document_status, document_number, business_date, currency,
                    subtotal, tax_total, total, customer_local_id, created_by_local_id,
                    confirmed_by_local_id, source_created_at, source_confirmed_at,
                    origin_terminal_local_id, received_at, cancelled_by_local_id,
                    source_cancelled_at, due_date, settled_by_origin, relationships_complete,
                    user_name, terminal_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (company_id, store_id, source_document_id) do update set
                    source_event_id = excluded.source_event_id,
                    source_payload_hash = excluded.source_payload_hash,
                    source_revision = excluded.source_revision,
                    schema_version = excluded.schema_version,
                    document_type = excluded.document_type,
                    document_status = excluded.document_status,
                    document_number = excluded.document_number,
                    business_date = excluded.business_date,
                    currency = excluded.currency,
                    subtotal = excluded.subtotal,
                    tax_total = excluded.tax_total,
                    total = excluded.total,
                    customer_local_id = excluded.customer_local_id,
                    created_by_local_id = excluded.created_by_local_id,
                    confirmed_by_local_id = excluded.confirmed_by_local_id,
                    source_created_at = excluded.source_created_at,
                    source_confirmed_at = excluded.source_confirmed_at,
                    origin_terminal_local_id = excluded.origin_terminal_local_id,
                    received_at = excluded.received_at,
                    cancelled_by_local_id = excluded.cancelled_by_local_id,
                    source_cancelled_at = excluded.source_cancelled_at,
                    due_date = excluded.due_date,
                    settled_by_origin = excluded.settled_by_origin,
                    relationships_complete = excluded.relationships_complete,
                    user_name = excluded.user_name,
                    terminal_name = excluded.terminal_name
                """, companyId, storeId, documentId, installationId, event.getEventId(), event.getPayloadHash(),
                snapshot.sourceRevision(), CommercialDocumentSnapshot.SCHEMA_VERSION,
                snapshot.type(), snapshot.status(), snapshot.number(), Date.valueOf(snapshot.businessDate()),
                snapshot.currency(), snapshot.subtotal(), snapshot.taxTotal(), snapshot.total(),
                snapshot.customerLocalId(), snapshot.createdByLocalId(), snapshot.confirmedByLocalId(),
                timestamp(snapshot.sourceCreatedAt()), timestamp(snapshot.sourceConfirmedAt()),
                snapshot.originTerminalLocalId(), Timestamp.from(event.getReceivedAt()),
                metadata.cancelledByLocalId(), timestamp(metadata.sourceCancelledAt()),
                metadata.dueDate() == null ? null : Date.valueOf(metadata.dueDate()),
                metadata.settledByOrigin(), metadata.relationshipsComplete(), metadata.userName(), metadata.terminalName());
        // Only the winning full snapshot replaces relations, under the same owner lock and transaction.
        jdbc.update("""
                delete from saas_commercial_document_relation
                 where company_id = ? and store_id = ? and source_document_id = ?
                """, companyId, storeId, documentId);
        if (!metadata.relationships().isEmpty()) {
            jdbc.batchUpdate("""
                    insert into saas_commercial_document_relation
                        (company_id, store_id, source_document_id, relation_type, origin_document_id)
                    values (?, ?, ?, ?, ?)
                    """, metadata.relationships(), metadata.relationships().size(), (statement, relation) -> {
                statement.setObject(1, companyId);
                statement.setObject(2, storeId);
                statement.setObject(3, documentId);
                statement.setString(4, relation.type());
                statement.setObject(5, relation.originDocumentId());
            });
        }
        rememberRevision(event, snapshot);
    }

    private void rememberRevision(SaasSyncEvent event, CommercialDocumentSnapshot snapshot) {
        jdbc.update("""
                insert into saas_commercial_document_revision
                    (company_id, store_id, source_document_id, source_revision, source_payload_hash, source_event_id)
                values (?, ?, ?, ?, ?, ?)
                """, event.getCompany().getId(), event.getStore().getId(), event.getEntityId(),
                snapshot.sourceRevision(), event.getPayloadHash(), event.getEventId());
    }

    private static void requireSameHash(String knownHash, String receivedHash) {
        if (!knownHash.equals(receivedHash)) {
            throw conflict("La revision documental ya existe con contenido diferente");
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private record Current(UUID installationId, long revision, String payloadHash) { }
}

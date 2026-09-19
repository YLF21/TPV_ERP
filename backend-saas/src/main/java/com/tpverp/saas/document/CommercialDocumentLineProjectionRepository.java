package com.tpverp.saas.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CommercialDocumentLineProjectionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectReader payloadReader;

    public CommercialDocumentLineProjectionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.payloadReader = mapper.readerFor(new TypeReference<Map<String, Object>>() {})
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    /** Caller owns the document advisory lock and transaction, including the winning header write. */
    void replace(UUID companyId, UUID storeId, UUID documentId, CommercialDocumentLines snapshot) {
        jdbc.update("""
                delete from saas_commercial_document_line
                 where company_id = ? and store_id = ? and source_document_id = ?
                """, companyId, storeId, documentId);
        if (!snapshot.lines().isEmpty()) {
            jdbc.batchUpdate("""
                    insert into saas_commercial_document_line (company_id, store_id, source_document_id,
                        line_position, product_local_id, line_type, product_code, product_name,
                        quantity, unit_price, discount_percent, line_total, price_tariff)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.lines(), 100, (statement, line) -> {
                statement.setObject(1, companyId);
                statement.setObject(2, storeId);
                statement.setObject(3, documentId);
                statement.setInt(4, line.position());
                statement.setObject(5, line.productLocalId());
                statement.setString(6, line.type());
                statement.setString(7, line.code());
                statement.setString(8, line.name());
                statement.setBigDecimal(9, line.quantity());
                statement.setBigDecimal(10, line.unitPrice());
                statement.setBigDecimal(11, line.discountPercent());
                statement.setBigDecimal(12, line.total());
                statement.setString(13, line.priceTariff());
            });
        }
        jdbc.update("""
                update saas_commercial_document set warehouse_local_id = ?, line_projection_status = ?
                 where company_id = ? and store_id = ? and source_document_id = ?
                """, snapshot.warehouseLocalId(), snapshot.status().name(), companyId, storeId, documentId);
    }

    List<DocumentKey> pending(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("line backfill batch: 1..1000");
        return jdbc.query("""
                select company_id, store_id, source_document_id from saas_commercial_document
                 where line_projection_status = 'PENDING'
                 order by company_id, store_id, source_document_id limit ?
                """, (row, index) -> new DocumentKey(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class)), limit);
    }

    /** Re-read after the same writer lock; never process the event/revision selected before a concurrent update. */
    @Transactional
    public boolean backfill(DocumentKey key) {
        CommercialDocumentProjectionRepository.lock(jdbc, key.companyId(), key.storeId(), key.documentId());
        var payloads = jdbc.query("""
                select e.payload from saas_commercial_document d
                left join saas_sync_event e on e.event_id = d.source_event_id
                   and e.company_id = d.company_id and e.store_id = d.store_id
                   and e.installation_id = d.source_installation_id and e.entity_id = d.source_document_id
                   and e.entity_type = 'DOCUMENTO' and e.payload_hash = d.source_payload_hash
                 where d.company_id = ? and d.store_id = ? and d.source_document_id = ?
                   and d.line_projection_status = 'PENDING'
                """, (row, index) -> row.getString(1), key.companyId(), key.storeId(), key.documentId());
        if (payloads.isEmpty()) return false;
        CommercialDocumentLines snapshot;
        try {
            snapshot = CommercialDocumentLines.parse(payloadReader.readValue(payloads.getFirst()));
        } catch (java.io.IOException | IllegalArgumentException exception) {
            snapshot = CommercialDocumentLines.invalid();
        }
        replace(key.companyId(), key.storeId(), key.documentId(), snapshot);
        return true;
    }

    record DocumentKey(UUID companyId, UUID storeId, UUID documentId) { }
}

package com.tpverp.backend.document;

import static com.tpverp.backend.document.DocumentSyncRecoveryApi.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "tpv.sync.document-recovery-enabled", havingValue = "true")
public class DocumentSyncRecoveryRepository {
    private final JdbcTemplate jdbc;
    public DocumentSyncRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<UUID> candidates(Scope scope, UUID afterId) {
        return jdbc.query("""
                select d.id from documento d join tienda t on t.id=d.tienda_id
                 where t.empresa_id=? and d.tienda_id=? and d.fecha between ? and ?
                   and d.creado_en<=? and d.estado<>'BORRADOR'
                   and d.tipo in ('TICKET','FACTURA_VENTA','RECTIFICATIVA_VENTA','ALBARAN_VENTA')
                   and not exists (select 1 from documento_sync_revision r where r.documento_id=d.id)
                   and (cast(? as uuid) is null or d.id>cast(? as uuid))
                 order by d.id limit 101
                """, (rs, n) -> rs.getObject(1, UUID.class), scope.companyId(), scope.storeId(),
                scope.dateFrom(), scope.dateTo(), Timestamp.from(scope.createdBefore()), afterId, afterId);
    }

    public boolean withinScope(Scope scope, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from documento d join tienda t on t.id=d.tienda_id
                 where d.id=? and t.empresa_id=? and d.tienda_id=? and d.fecha between ? and ?
                   and d.creado_en<=? and d.estado<>'BORRADOR'
                   and d.tipo in ('TICKET','FACTURA_VENTA','RECTIFICATIVA_VENTA','ALBARAN_VENTA'))
                """, Boolean.class, id, scope.companyId(), scope.storeId(), scope.dateFrom(), scope.dateTo(),
                Timestamp.from(scope.createdBefore())));
    }

    public boolean published(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from documento_sync_revision where documento_id=?)", Boolean.class, id));
    }

    public Receipt latestReceipt(Scope scope, UUID id) {
        var found = jdbc.query("""
                select o.event_id, r.source_revision, o.estado
                  from documento_sync_revision r join sync_outbox o on o.entidad_id=r.documento_id
                 where r.documento_id=? and o.empresa_id=? and o.tienda_id=? and o.tipo_entidad='DOCUMENTO'
                   and o.payload->'schemaVersion'='2'::jsonb
                   and o.payload->>'sourceRevision'=r.source_revision::text
                 order by o.creado_en, o.event_id limit 1
                """, (rs, n) -> new Receipt(id, rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                id, scope.companyId(), scope.storeId());
        if (found.isEmpty()) throw DocumentSyncRecoveryException.conflict("DOCUMENT_RECOVERY_LOCAL_RECEIPT_MISSING");
        return found.getFirst();
    }

    public boolean receiptMatches(Scope scope, Receipt receipt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from sync_outbox o where o.event_id=? and o.entidad_id=?
                 and o.empresa_id=? and o.tienda_id=? and o.tipo_entidad='DOCUMENTO'
                 and o.payload->'schemaVersion'='2'::jsonb and o.payload->>'sourceRevision'=?)
                """, Boolean.class, receipt.eventId(), receipt.documentId(), scope.companyId(), scope.storeId(),
                receipt.sourceRevision()));
    }
}

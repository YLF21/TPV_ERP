package com.tpverp.backend.document;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Allocates and locks the document's publication revision in the business transaction. */
@Repository
public class DocumentSyncRevisionRepository {

    private final JdbcTemplate jdbc;

    public DocumentSyncRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims revision one without changing a revision already allocated by another publication. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaimInitialRevision(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return !jdbc.query("""
                insert into documento_sync_revision (documento_id, source_revision)
                values (?, 1)
                on conflict (documento_id) do nothing
                returning 1
                """, (rs, index) -> rs.getInt(1), documentId).isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long nextRevision(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        Long revision = jdbc.queryForObject("""
                insert into documento_sync_revision (documento_id, source_revision)
                values (?, 1)
                on conflict (documento_id) do update
                    set source_revision = documento_sync_revision.source_revision + 1
                returning source_revision
                """, Long.class, documentId);
        if (revision == null || revision < 1) {
            throw new IllegalStateException("No se pudo asignar la revision documental");
        }
        return revision;
    }
}

package com.tpverp.backend.document;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommercialDocumentRepository extends JpaRepository<CommercialDocument, UUID> {

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    List<CommercialDocument> findAllByTiendaIdAndTipoInOrderByFechaDesc(
            UUID tiendaId, Collection<CommercialDocumentType> tipos);
}

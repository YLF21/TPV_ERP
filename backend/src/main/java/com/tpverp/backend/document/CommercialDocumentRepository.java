package com.tpverp.backend.document;

import java.util.Collection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommercialDocumentRepository extends JpaRepository<CommercialDocument, UUID> {

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    List<CommercialDocument> findAllByTiendaIdAndTipoInOrderByFechaDesc(
            UUID tiendaId, Collection<CommercialDocumentType> tipos);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    List<CommercialDocument> findAllByTiendaIdAndFecha(UUID tiendaId, LocalDate fecha);
}

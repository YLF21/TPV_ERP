package com.tpverp.backend.document;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentoRepository extends JpaRepository<Documento, UUID> {

    List<Documento> findAllByTiendaIdAndTipoInOrderByFechaDesc(
            UUID tiendaId, Collection<TipoDocumento> tipos);
}

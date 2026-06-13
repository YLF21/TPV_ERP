package com.tpverp.backend.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface ContadorDocumentoRepository extends JpaRepository<ContadorDocumento, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContadorDocumento> findByTiendaIdAndTipoAndPeriodo(
            UUID tiendaId, String tipo, String periodo);
}

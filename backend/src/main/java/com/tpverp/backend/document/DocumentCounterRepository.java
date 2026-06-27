package com.tpverp.backend.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentCounter> findByTiendaIdAndTipoAndPeriodo(
            UUID tiendaId, String tipo, String periodo);
}

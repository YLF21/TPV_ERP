package com.tpverp.backend.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, UUID> {

    List<Auditoria> findByTiendaIdAndCreadaEnBetweenOrderByCreadaEnDesc(
            UUID tiendaId, Instant desde, Instant hasta);

    java.util.Optional<Auditoria> findByIdAndTiendaId(UUID id, UUID tiendaId);

    long deleteByCreadaEnBefore(Instant limite);
}

package com.tpverp.backend.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    List<AuditEntry> findByTiendaIdAndCreadaEnBetweenOrderByCreadaEnDesc(
            UUID tiendaId, Instant desde, Instant hasta);

    java.util.Optional<AuditEntry> findByIdAndTiendaId(UUID id, UUID tiendaId);

    long deleteByCreadaEnBefore(Instant limite);
}

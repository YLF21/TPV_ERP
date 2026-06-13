package com.tpverp.backend.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, UUID> {

    List<Auditoria> findByCreadaEnBetweenOrderByCreadaEnDesc(Instant desde, Instant hasta);

    long deleteByCreadaEnBefore(Instant limite);
}

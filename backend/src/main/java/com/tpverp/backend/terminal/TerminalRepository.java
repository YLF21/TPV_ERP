package com.tpverp.backend.terminal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    Optional<Terminal> findByTiendaIdAndNombreIgnoreCase(UUID tiendaId, String nombre);

    List<Terminal> findByTiendaIdAndActivaTrue(UUID tiendaId);
}

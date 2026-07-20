package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByTiendaIdAndNombre(UUID tiendaId, String nombre);

    Optional<Role> findByIdAndTiendaId(UUID id, UUID tiendaId);

    List<Role> findAllByTiendaIdOrderByNombre(UUID tiendaId);

    List<Role> findAllByTiendaIdAndProtegidoFalseOrderByNombre(UUID tiendaId);
}

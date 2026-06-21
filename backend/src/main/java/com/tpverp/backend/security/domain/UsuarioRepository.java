package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByTiendaIdAndNombre(UUID tiendaId, String nombre);

    Optional<Usuario> findByIdAndTiendaId(UUID id, UUID tiendaId);

    List<Usuario> findAllByTiendaIdOrderByNombre(UUID tiendaId);
}

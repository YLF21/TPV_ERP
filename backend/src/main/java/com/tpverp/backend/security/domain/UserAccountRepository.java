package com.tpverp.backend.security.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTiendaIdAndNombre(UUID tiendaId, String nombre);

    Optional<UserAccount> findByIdAndTiendaId(UUID id, UUID tiendaId);

    List<UserAccount> findAllByTiendaIdOrderByNombre(UUID tiendaId);
}

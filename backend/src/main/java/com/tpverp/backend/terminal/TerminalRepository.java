package com.tpverp.backend.terminal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends JpaRepository<Terminal, UUID> {

    Optional<Terminal> findByTiendaIdAndNombreIgnoreCase(UUID tiendaId, String nombre);

    Optional<Terminal> findByTiendaIdAndTipo(UUID tiendaId, TerminalType tipo);

    Optional<Terminal> findByIdAndTiendaId(UUID id, UUID tiendaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select terminal from Terminal terminal where terminal.id = :id and terminal.tienda.id = :storeId")
    Optional<Terminal> findForCashSessionPreparation(
            @Param("id") UUID id,
            @Param("storeId") UUID storeId);

    List<Terminal> findAllByTiendaIdOrderByNombre(UUID tiendaId);

    List<Terminal> findByTiendaIdAndActivaTrue(UUID tiendaId);
}

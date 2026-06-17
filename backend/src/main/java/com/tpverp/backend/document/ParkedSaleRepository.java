package com.tpverp.backend.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkedSaleRepository extends JpaRepository<ParkedSale, UUID> {

    Optional<ParkedSale> findByIdAndTiendaId(UUID id, UUID tiendaId);

    List<ParkedSale> findAllByTiendaIdOrderByCreadoEnDesc(UUID tiendaId);
}

package com.tpverp.backend.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ParkedSaleRepository extends JpaRepository<ParkedSale, UUID> {

    Optional<ParkedSale> findByIdAndTiendaId(UUID id, UUID tiendaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sale from ParkedSale sale where sale.id = :id and sale.tiendaId = :storeId")
    Optional<ParkedSale> findLockedByIdAndStoreId(
            @Param("id") UUID id, @Param("storeId") UUID storeId);

    List<ParkedSale> findAllByTiendaIdOrderByCreadoEnDesc(UUID tiendaId);
}

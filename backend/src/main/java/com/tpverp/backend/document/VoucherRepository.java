package com.tpverp.backend.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    Optional<Voucher> findByTiendaIdAndCode(UUID tiendaId, String code);

    Optional<Voucher> findByTiendaIdAndCodeIgnoreCase(UUID tiendaId, String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select voucher from Voucher voucher where voucher.tiendaId = :storeId and voucher.code = :code")
    Optional<Voucher> findLockedByTiendaIdAndCode(
            @Param("storeId") UUID tiendaId, @Param("code") String code);

    List<Voucher> findAllByTiendaIdOrderByCreatedAtDesc(UUID tiendaId);

    @Query(value = """
            select *
              from vale
             where tienda_id = :storeId
               and tickets_origen @> jsonb_build_array(cast(:ticketNumber as text))
             order by creado_en
            """, nativeQuery = true)
    List<Voucher> findAllByOriginTicket(
            @Param("storeId") UUID storeId,
            @Param("ticketNumber") String ticketNumber);

    @Query(value = """
            select *
              from vale
             where tienda_id = :storeId
               and tickets_origen @> jsonb_build_array(cast(:ticketNumber as text))
             order by creado_en
             for update
            """, nativeQuery = true)
    List<Voucher> findAllLockedByOriginTicket(
            @Param("storeId") UUID storeId,
            @Param("ticketNumber") String ticketNumber);
}

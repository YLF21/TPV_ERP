package com.tpverp.backend.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentPaymentRepository extends JpaRepository<DocumentPayment, UUID> {
    List<DocumentPayment> findAllByDocumentoId(UUID documentId);

    @Query("""
            select payment
            from DocumentPayment payment
            join fetch payment.documento document
            join fetch payment.metodoPago method
            where document.tiendaId = :storeId
              and payment.creadoEn >= :from
              and payment.creadoEn < :to
            order by payment.creadoEn asc
            """)
    List<DocumentPayment> findAllByStoreAndCreatedBetween(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}

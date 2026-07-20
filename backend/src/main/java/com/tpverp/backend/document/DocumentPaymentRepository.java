package com.tpverp.backend.document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentPaymentRepository extends JpaRepository<DocumentPayment, UUID> {
    Optional<DocumentPayment> findByRequestId(UUID requestId);

    List<DocumentPayment> findAllByDocumentoId(UUID documentId);

    @Query("""
            select payment
            from DocumentPayment payment
            join fetch payment.documento document
            join fetch payment.metodoPago method
            where document.tiendaId = :storeId
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA)
              and document.estado not in (
                  com.tpverp.backend.document.DocumentStatus.BORRADOR,
                  com.tpverp.backend.document.DocumentStatus.ANULADO)
              and document.clienteId is not null
              and (:filterCustomer = false or document.clienteId = :customerId)
              and payment.creadoEn >= :from
              and payment.creadoEn < :to
              and (:filterPaymentMethod = false or method.id = :paymentMethodId)
              and not exists (
                  select relation.documento.id
                  from DocumentRelation relation
                  where relation.origen.id = document.id
                    and relation.tipo = com.tpverp.backend.document.DocumentRelationType.FACTURA_DE
                    and relation.documento.estado not in (
                        com.tpverp.backend.document.DocumentStatus.BORRADOR,
                        com.tpverp.backend.document.DocumentStatus.ANULADO)
              )
            order by payment.creadoEn desc, payment.id desc
            """)
    List<DocumentPayment> findCustomerReceivablePaymentHistory(
            @Param("storeId") UUID storeId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("filterPaymentMethod") boolean filterPaymentMethod,
            @Param("paymentMethodId") UUID paymentMethodId,
            @Param("filterCustomer") boolean filterCustomer,
            @Param("customerId") UUID customerId);

    @Query("""
            select payment
            from DocumentPayment payment
            join fetch payment.documento document
            join fetch payment.metodoPago method
            where payment.id = :paymentId
              and document.id = :documentId
              and document.tiendaId = :storeId
              and document.clienteId is not null
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA)
              and document.estado not in (
                  com.tpverp.backend.document.DocumentStatus.BORRADOR,
                  com.tpverp.backend.document.DocumentStatus.ANULADO)
              and not exists (
                  select relation.documento.id
                  from DocumentRelation relation
                  where relation.origen.id = document.id
                    and relation.tipo = com.tpverp.backend.document.DocumentRelationType.FACTURA_DE
                    and relation.documento.estado not in (
                        com.tpverp.backend.document.DocumentStatus.BORRADOR,
                        com.tpverp.backend.document.DocumentStatus.ANULADO)
              )
            """)
    Optional<DocumentPayment> findCustomerReceivablePayment(
            @Param("documentId") UUID documentId,
            @Param("paymentId") UUID paymentId,
            @Param("storeId") UUID storeId);

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

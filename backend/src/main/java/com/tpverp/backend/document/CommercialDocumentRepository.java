package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommercialDocumentRepository extends JpaRepository<CommercialDocument, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select document
            from CommercialDocument document
            left join fetch document.pagos
            where document.id = :id and document.tiendaId = :storeId
            """)
    Optional<CommercialDocument> findLockedDocument(
            @Param("id") UUID id, @Param("storeId") UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select document
            from CommercialDocument document
            left join fetch document.pagos
            where document.id = :id
              and document.tiendaId = :storeId
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
    Optional<CommercialDocument> findLockedReceivable(
            @Param("id") UUID id, @Param("storeId") UUID storeId);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA)
              and document.estado in (
                  com.tpverp.backend.document.DocumentStatus.PENDIENTE,
                  com.tpverp.backend.document.DocumentStatus.PARCIAL)
              and document.clienteId is not null
              and not exists (
                  select relation.documento.id
                  from DocumentRelation relation
                  where relation.origen.id = document.id
                    and relation.tipo = com.tpverp.backend.document.DocumentRelationType.FACTURA_DE
                    and relation.documento.estado not in (
                        com.tpverp.backend.document.DocumentStatus.BORRADOR,
                        com.tpverp.backend.document.DocumentStatus.ANULADO)
              )
            order by document.fechaVencimiento asc, document.fecha desc, document.numero desc
            """)
    List<CommercialDocument> findCustomerReceivables(@Param("storeId") UUID storeId);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
            from CommercialDocument document
            where document.id = :id
              and document.tiendaId = :storeId
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA)
              and document.estado in (
                  com.tpverp.backend.document.DocumentStatus.PENDIENTE,
                  com.tpverp.backend.document.DocumentStatus.PARCIAL)
              and document.clienteId is not null
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
    Optional<CommercialDocument> findCustomerReceivable(
            @Param("id") UUID id, @Param("storeId") UUID storeId);

    @EntityGraph(attributePaths = {"lineas"})
    @Query("""
            select document from CommercialDocument document
            where document.id = :id and document.tiendaId = :storeId
              and document.clienteId is not null
              and document.tipo in (
                com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA)
              and document.estado not in (
                com.tpverp.backend.document.DocumentStatus.BORRADOR,
                com.tpverp.backend.document.DocumentStatus.ANULADO)
            """)
    Optional<CommercialDocument> findCustomerDocumentForPrint(
            @Param("id") UUID id, @Param("storeId") UUID storeId);

    java.util.Optional<CommercialDocument> findByPaymentTerminalRefundOperationId(UUID operationId);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    List<CommercialDocument> findAllByTiendaIdAndTipoInOrderByFechaDesc(
            UUID tiendaId, Collection<CommercialDocumentType> tipos);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    List<CommercialDocument> findAllByTiendaIdAndFecha(UUID tiendaId, LocalDate fecha);

    @EntityGraph(attributePaths = {"lineas"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.fecha between :from and :to
              and document.tipo in :types
              and (:warehouseId is null or document.almacenId = :warehouseId)
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
            order by document.fecha desc
            """)
    List<CommercialDocument> findTopSalesDocuments(
            @Param("storeId") UUID storeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("types") Collection<CommercialDocumentType> types,
            @Param("warehouseId") UUID warehouseId);

    @Query(value = """
            select document.id as "documentId",
                   document.tipo as "documentType",
                   document.numero as "documentNumber",
                   document.estado as "status",
                   (document.fecha::timestamp at time zone store.timezone) as "occurredAt",
                   document.cliente_id as "customerId",
                   customer.nombre_fiscal as "customerName",
                   line.cantidad as "quantity",
                   line.precio_unitario as "unitPrice",
                   line.descuento as "discountPercent",
                   line.total as "lineTotal",
                   coalesce(document.anulado_por, document.confirmado_por, document.creado_por) as "userId",
                   actor.user_name as "userName",
                   document.tienda_id as "storeId",
                   coalesce(store.nombre, company.razon_social) as "storeName",
                   document.almacen_id as "warehouseId",
                   warehouse.nombre as "warehouseName"
            from documento document
            join documento_linea line on line.documento_id = document.id
            join tienda store on store.id = document.tienda_id
            join empresa company on company.id = store.empresa_id
            join almacen warehouse on warehouse.id = document.almacen_id
            left join cliente customer on customer.id = document.cliente_id
            left join usuario actor
                on actor.id = coalesce(document.anulado_por, document.confirmado_por, document.creado_por)
            where document.tienda_id = :storeId
              and line.producto_id = :productId
              and document.tipo in ('TICKET', 'FACTURA_VENTA', 'ALBARAN_VENTA', 'RECTIFICATIVA_VENTA')
              and document.estado <> 'BORRADOR'
              and (cast(:fromDate as date) is null or document.fecha >= cast(:fromDate as date))
              and (cast(:toDate as date) is null or document.fecha <= cast(:toDate as date))
            order by document.fecha desc,
                     coalesce(document.confirmado_en, document.creado_en) desc,
                     line.posicion
            """, nativeQuery = true)
    List<SalesHistoryProjection> findProductSalesHistory(
            @Param("storeId") UUID storeId,
            @Param("productId") UUID productId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    interface SalesHistoryProjection {

        UUID getDocumentId();

        String getDocumentType();

        String getDocumentNumber();

        String getStatus();

        Instant getOccurredAt();

        UUID getCustomerId();

        String getCustomerName();

        BigDecimal getQuantity();

        BigDecimal getUnitPrice();

        BigDecimal getDiscountPercent();

        BigDecimal getLineTotal();

        UUID getUserId();

        String getUserName();

        UUID getStoreId();

        String getStoreName();

        UUID getWarehouseId();

        String getWarehouseName();
    }

    @EntityGraph(attributePaths = {"lineas"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.tipo = com.tpverp.backend.document.CommercialDocumentType.FACTURA_COMPRA
              and document.estado <> com.tpverp.backend.document.DocumentStatus.ANULADO
            order by document.fecha desc, document.numero desc
            """)
    List<CommercialDocument> findPurchaseInvoicesForBulkEdit(UUID storeId);

    @EntityGraph(attributePaths = {"lineas"})
    @Query("""
            select document
            from CommercialDocument document
            where document.id = :invoiceId
              and document.tiendaId = :storeId
              and document.tipo = com.tpverp.backend.document.CommercialDocumentType.FACTURA_COMPRA
              and document.estado <> com.tpverp.backend.document.DocumentStatus.ANULADO
            """)
    Optional<CommercialDocument> findPurchaseInvoiceForBulkEdit(UUID storeId, UUID invoiceId);

    @EntityGraph(attributePaths = {"lineas"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.tipo = com.tpverp.backend.document.CommercialDocumentType.ALBARAN_COMPRA
              and document.estado <> com.tpverp.backend.document.DocumentStatus.ANULADO
            order by document.fecha desc, document.numero desc
            """)
    List<CommercialDocument> findPurchaseDeliveryNotesForBulkEdit(UUID storeId);

    @EntityGraph(attributePaths = {"lineas"})
    @Query("""
            select document
            from CommercialDocument document
            where document.id = :deliveryNoteId
              and document.tiendaId = :storeId
              and document.tipo = com.tpverp.backend.document.CommercialDocumentType.ALBARAN_COMPRA
              and document.estado <> com.tpverp.backend.document.DocumentStatus.ANULADO
            """)
    Optional<CommercialDocument> findPurchaseDeliveryNoteForBulkEdit(
            UUID storeId, UUID deliveryNoteId);
}

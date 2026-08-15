package com.tpverp.backend.document;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommercialDocumentRepository extends JpaRepository<CommercialDocument, UUID> {
    @Query(value = """
            select 1
              from (select pg_advisory_xact_lock(
                    hashtextextended(cast(:lockKey as text), 0))) locked
            """, nativeQuery = true)
    Integer lockSerialNumber(@Param("lockKey") String lockKey);

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
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.TICKET)
              and (document.tipo <> com.tpverp.backend.document.CommercialDocumentType.TICKET
                  or document.cuentaCobrar = true)
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
            where document.tiendaId = :storeId
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.TICKET)
              and (document.tipo <> com.tpverp.backend.document.CommercialDocumentType.TICKET
                  or document.cuentaCobrar = true)
              and document.estado in (
                  com.tpverp.backend.document.DocumentStatus.PENDIENTE,
                  com.tpverp.backend.document.DocumentStatus.PARCIAL,
                  com.tpverp.backend.document.DocumentStatus.PAGADO)
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
            order by document.fecha desc, document.numero desc
            """)
    List<CommercialDocument> findCustomerReceivablesIncludingPaid(
            @Param("storeId") UUID storeId);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.clienteId = :customerId
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.TICKET)
              and (document.tipo <> com.tpverp.backend.document.CommercialDocumentType.TICKET
                  or document.cuentaCobrar = true)
              and document.estado in (
                  com.tpverp.backend.document.DocumentStatus.PENDIENTE,
                  com.tpverp.backend.document.DocumentStatus.PARCIAL,
                  com.tpverp.backend.document.DocumentStatus.PAGADO)
              and not exists (
                  select relation.documento.id
                  from DocumentRelation relation
                  where relation.origen.id = document.id
                    and relation.tipo = com.tpverp.backend.document.DocumentRelationType.FACTURA_DE
                    and relation.documento.estado not in (
                        com.tpverp.backend.document.DocumentStatus.BORRADOR,
                        com.tpverp.backend.document.DocumentStatus.ANULADO)
              )
            order by document.fecha desc, document.numero desc
            """)
    List<CommercialDocument> findCustomerAccountDocuments(
            @Param("storeId") UUID storeId,
            @Param("customerId") UUID customerId);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
            from CommercialDocument document
            where document.id = :id
              and document.tiendaId = :storeId
              and document.tipo in (
                  com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA,
                  com.tpverp.backend.document.CommercialDocumentType.TICKET)
              and (document.tipo <> com.tpverp.backend.document.CommercialDocumentType.TICKET
                  or document.cuentaCobrar = true)
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
                com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA,
                com.tpverp.backend.document.CommercialDocumentType.RECTIFICATIVA_VENTA,
                com.tpverp.backend.document.CommercialDocumentType.TICKET)
              and (document.tipo <> com.tpverp.backend.document.CommercialDocumentType.TICKET
                  or document.cuentaCobrar = true)
              and document.estado not in (
                com.tpverp.backend.document.DocumentStatus.BORRADOR,
                com.tpverp.backend.document.DocumentStatus.ANULADO)
            """)
    Optional<CommercialDocument> findCustomerDocumentForPrint(
            @Param("id") UUID id, @Param("storeId") UUID storeId);

    java.util.Optional<CommercialDocument> findByPaymentTerminalRefundOperationId(UUID operationId);

    java.util.Optional<CommercialDocument> findByReturnRequestId(UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "lineas")
    @Query("select document from CommercialDocument document where document.id = :id and document.tiendaId = :storeId")
    Optional<CommercialDocument> findLockedRefundSource(@Param("id") UUID id, @Param("storeId") UUID storeId);

    @Query(value = """
            select coalesce(sum(abs(line.cantidad)), 0)
              from documento_linea line
              join documento document on document.id = line.documento_id
              left join factura_rectificacion_venta rectification
                on rectification.documento_id = document.id
             where line.original_document_line_id = :lineId
               and document.estado not in ('BORRADOR', 'ANULADO')
               and (document.tipo = 'TICKET' or rectification.afecta_stock = true)
            """, nativeQuery = true)
    BigDecimal confirmedRefundedQuantity(@Param("lineId") UUID lineId);

    @Query(value = """
            select coalesce(sum(abs(rectified.total)), 0)
              from documento_relacion relation
              join documento rectified on rectified.id = relation.documento_id
             where relation.origen_id = :documentId
               and relation.tipo = 'RECTIFICA'
               and rectified.estado not in ('BORRADOR', 'ANULADO')
               and rectified.total <= 0
            """, nativeQuery = true)
    BigDecimal confirmedReturnAmount(@Param("documentId") UUID documentId);

    @Query(value = """
            select line.impuestos_incluidos as "taxIncluded",
                   line.regimen_impuesto as "taxRegime",
                   line.porcentaje_impuesto as "taxPercent",
                   coalesce(sum(-line.total *
                       (1 - rectified.descuento_global / 100)), 0) as amount
              from documento_relacion relation
              join documento rectified on rectified.id = relation.documento_id
              join documento_linea line on line.documento_id = rectified.id
             where relation.origen_id = :documentId
               and relation.tipo = 'RECTIFICA'
               and rectified.estado not in ('BORRADOR', 'ANULADO')
               and rectified.total <= 0
             group by line.impuestos_incluidos,
                      line.regimen_impuesto,
                      line.porcentaje_impuesto
            """, nativeQuery = true)
    List<ConfirmedReturnTaxTotal> confirmedReturnTaxTotals(
            @Param("documentId") UUID documentId);

    interface ConfirmedReturnTaxTotal {
        boolean getTaxIncluded();

        String getTaxRegime();

        BigDecimal getTaxPercent();

        BigDecimal getAmount();
    }

    @EntityGraph(attributePaths = "lineas")
    Optional<CommercialDocument> findByIdAndTiendaId(UUID id, UUID tiendaId);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.tipo in :types
            order by document.fecha desc,
                     coalesce(document.confirmadoEn, document.creadoEn) desc,
                     cast(document.id as string) desc
            """)
    List<CommercialDocument> findAllByStoreAndTypesOrderByRecency(
            @Param("storeId") UUID storeId,
            @Param("types") Collection<CommercialDocumentType> types);

    @Query("""
            select document.id
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.estado = com.tpverp.backend.document.DocumentStatus.BORRADOR
              and document.tipo in :types
            order by document.creadoEn desc, cast(document.id as string) desc
            """)
    List<UUID> findSalesDraftIds(
            @Param("storeId") UUID storeId,
            @Param("types") Collection<CommercialDocumentType> types,
            Pageable pageable);

    @EntityGraph(attributePaths = "lineas")
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.id in :ids
            """)
    List<CommercialDocument> findSalesDraftsWithLines(
            @Param("storeId") UUID storeId,
            @Param("ids") Collection<UUID> ids);

    @EntityGraph(attributePaths = "lineas")
    Optional<CommercialDocument> findByTiendaIdAndTipoAndNumeroIgnoreCase(
            UUID tiendaId, CommercialDocumentType tipo, String numero);

    List<CommercialDocument> findAllByTiendaIdAndNumeroIgnoreCase(
            UUID tiendaId, String numero);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
              from CommercialDocument document
             where document.id = :id
               and document.tiendaId = :storeId
            """)
    Optional<CommercialDocument> findByIdAndTiendaIdWithPayments(
            @Param("id") UUID id,
            @Param("storeId") UUID storeId);

    @Query("""
            select distinct line
              from DocumentLine line
              left join fetch line.serialNumbers
             where line.documento.id = :documentId
            """)
    List<DocumentLine> loadLineSerialNumbers(
            @Param("documentId") UUID documentId);

    @Query("""
            select document.id
              from CommercialDocument document
             where document.tiendaId = :storeId
               and document.terminalOrigenId = :terminalId
               and document.tipo = com.tpverp.backend.document.CommercialDocumentType.TICKET
               and document.estado = com.tpverp.backend.document.DocumentStatus.CONFIRMADO
               and not exists (
                   select relation.documento.id
                     from DocumentRelation relation
                    where relation.origen.id = document.id
                      and relation.tipo in (
                          com.tpverp.backend.document.DocumentRelationType.FACTURA_DE,
                          com.tpverp.backend.document.DocumentRelationType.RECTIFICA)
                      and relation.documento.estado not in (
                          com.tpverp.backend.document.DocumentStatus.BORRADOR,
                          com.tpverp.backend.document.DocumentStatus.ANULADO)
               )
             order by coalesce(document.confirmadoEn, document.creadoEn) desc,
                      document.id desc
            """)
    List<UUID> findLatestCancellableTicketIds(
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            Pageable pageable);

    @Query("""
            select document.id
              from CommercialDocument document
             where document.tiendaId = :storeId
               and document.terminalOrigenId = :terminalId
               and document.tipo = com.tpverp.backend.document.CommercialDocumentType.TICKET
               and document.estado = com.tpverp.backend.document.DocumentStatus.CONFIRMADO
               and not exists (
                   select relation.documento.id
                     from DocumentRelation relation
                    where relation.origen.id = document.id
                      and relation.tipo in (
                          com.tpverp.backend.document.DocumentRelationType.FACTURA_DE,
                          com.tpverp.backend.document.DocumentRelationType.RECTIFICA)
                      and relation.documento.estado not in (
                          com.tpverp.backend.document.DocumentStatus.BORRADOR,
                          com.tpverp.backend.document.DocumentStatus.ANULADO)
               )
             order by coalesce(document.confirmadoEn, document.creadoEn) desc,
                      document.id desc
            """)
    List<UUID> findLatestConvertibleTicketIds(
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            Pageable pageable);

    @Query("""
            select document.id
              from CommercialDocument document
             where document.tiendaId = :storeId
               and document.terminalOrigenId = :terminalId
               and document.tipo = com.tpverp.backend.document.CommercialDocumentType.TICKET
               and document.estado in (
                   com.tpverp.backend.document.DocumentStatus.CONFIRMADO,
                   com.tpverp.backend.document.DocumentStatus.ANULADO)
               and document.total > 0
               and exists (
                   select productLine.id
                     from DocumentLine productLine
                    where productLine.documento = document
                      and productLine.lineType = com.tpverp.backend.document.DocumentLineType.PRODUCT)
               and not exists (
                   select invalidLine.id
                     from DocumentLine invalidLine
                    where invalidLine.documento = document
                      and (invalidLine.lineType = com.tpverp.backend.document.DocumentLineType.RETURN_ADJUSTMENT
                           or invalidLine.originalDocumentLineId is not null
                           or (invalidLine.lineType = com.tpverp.backend.document.DocumentLineType.PRODUCT
                               and invalidLine.cantidad <= 0)))
               and not exists (
                   select relation.id
                     from DocumentRelation relation
                    where relation.documento = document
                      and relation.tipo = com.tpverp.backend.document.DocumentRelationType.COMPENSA)
             order by coalesce(document.confirmadoEn, document.creadoEn) desc,
                      document.id desc
            """)
    List<UUID> findLatestPositiveConfirmedTicketIds(
            @Param("storeId") UUID storeId,
            @Param("terminalId") UUID terminalId,
            Pageable pageable);

    @Query("""
            select (count(relation) > 0)
              from DocumentRelation relation
             where relation.documento.id = :documentId
               and relation.tipo = com.tpverp.backend.document.DocumentRelationType.COMPENSA
            """)
    boolean isExchangeSale(@Param("documentId") UUID documentId);

    @Query("""
            select serial
              from DocumentLine line
              join line.serialNumbers serial
             where line.originalDocumentLineId = :lineId
               and line.documento.estado not in (
                   com.tpverp.backend.document.DocumentStatus.BORRADOR,
                   com.tpverp.backend.document.DocumentStatus.ANULADO)
            """)
    List<String> confirmedRefundedSerialNumbers(@Param("lineId") UUID lineId);

    @Query("""
            select upper(trim(serial))
              from DocumentLine line
              join line.serialNumbers serial
             where line.documento.tiendaId = :storeId
               and line.documento.tipo in (
                   com.tpverp.backend.document.CommercialDocumentType.TICKET,
                   com.tpverp.backend.document.CommercialDocumentType.ALBARAN_VENTA,
                   com.tpverp.backend.document.CommercialDocumentType.FACTURA_VENTA)
               and line.documento.estado not in (
                   com.tpverp.backend.document.DocumentStatus.BORRADOR,
                   com.tpverp.backend.document.DocumentStatus.ANULADO)
               and line.documento.origenStock = true
               and line.lineType = com.tpverp.backend.document.DocumentLineType.PRODUCT
               and line.cantidad > 0
               and upper(trim(serial)) in :serialNumbers
            """)
    List<String> usedSerialNumbers(
            @Param("storeId") UUID storeId,
            @Param("serialNumbers") Collection<String> serialNumbers);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.tipo in :types
            order by document.fecha desc,
                     coalesce(document.confirmadoEn, document.creadoEn) desc,
                     cast(document.id as string) desc
            """)
    List<CommercialDocument> findReportDocuments(
            @Param("storeId") UUID storeId,
            @Param("types") Collection<CommercialDocumentType> types,
            Pageable pageable);

    @EntityGraph(attributePaths = {"pagos", "pagos.metodoPago"})
    @Query("""
            select document
            from CommercialDocument document
            where document.tiendaId = :storeId
              and document.tipo in :types
              and (
                  document.fecha < :cursorDate
                  or (
                      document.fecha = :cursorDate
                      and (
                          coalesce(document.confirmadoEn, document.creadoEn) < :cursorOccurredAt
                          or (
                              coalesce(document.confirmadoEn, document.creadoEn) = :cursorOccurredAt
                              and cast(document.id as string) < :cursorId
                          )
                      )
                  )
              )
            order by document.fecha desc,
                     coalesce(document.confirmadoEn, document.creadoEn) desc,
                     cast(document.id as string) desc
            """)
    List<CommercialDocument> findReportDocumentsAfter(
            @Param("storeId") UUID storeId,
            @Param("types") Collection<CommercialDocumentType> types,
            @Param("cursorDate") LocalDate cursorDate,
            @Param("cursorOccurredAt") Instant cursorOccurredAt,
            @Param("cursorId") String cursorId,
            Pageable pageable);

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
                   coalesce(document.confirmado_en, document.creado_en) as "occurredAt",
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
    List<CommercialDocument> findByTiendaIdAndTipoOrderByFechaDescNumeroDesc(
            UUID storeId,
            CommercialDocumentType type);

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

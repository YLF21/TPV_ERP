package com.tpverp.backend.pdawork;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public interface PdaWorkRepository extends JpaRepository<PdaWorkItem, UUID>, JpaSpecificationExecutor<PdaWorkItem> {
    List<PdaWorkItem> findByStoreIdOrderByCreatedAtDesc(UUID storeId);
    List<PdaWorkItem> findByStoreIdAndStatusOrderByCreatedAtDesc(UUID storeId, PdaWorkStatus status);
    Optional<PdaWorkItem> findByIdAndStoreId(UUID id, UUID storeId);
    Optional<PdaWorkItem> findByStoreIdAndClientRequestId(UUID storeId, UUID clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select work from PdaWorkItem work where work.id=:id and work.storeId=:storeId")
    Optional<PdaWorkItem> findByIdAndStoreIdForUpdate(@Param("id") UUID id, @Param("storeId") UUID storeId);

    @Modifying
    @Query(value = """
            insert into pda_trabajo_operativo
              (id, tienda_id, tipo, estado, titulo, referencia, producto_codigo, almacen_id,
               cantidad, numero_lote, caduca_el, ubicacion, prioridad, notas,
               evidencia_nombre, evidencia_tipo, evidencia_datos, creado_por, creado_en,
               client_request_id, client_request_fingerprint, asignado_a, vence_en,
               ubicacion_origen, ubicacion_destino, comprobacion_id, documento_id, producto_id, version)
            values
              (:id, :storeId, :type, :status, :title, :reference, :productCode, :warehouseId,
               :quantity, :lotNumber, :expiryDate, :location, :priority, :notes,
               :evidenceName, :evidenceType, null, :createdBy, :createdAt,
               :clientRequestId, :fingerprint, :assignedTo, :dueAt,
               :sourceLocation, :destinationLocation, :goodsCheckId, :documentId, :productId, 0)
            on conflict (tienda_id, client_request_id) where client_request_id is not null do nothing
            """, nativeQuery = true)
    int insertIdempotent(@Param("id") UUID id, @Param("storeId") UUID storeId,
            @Param("type") String type, @Param("status") String status, @Param("title") String title,
            @Param("reference") String reference, @Param("productCode") String productCode,
            @Param("warehouseId") UUID warehouseId, @Param("quantity") BigDecimal quantity,
            @Param("lotNumber") String lotNumber, @Param("expiryDate") LocalDate expiryDate,
            @Param("location") String location, @Param("priority") String priority,
            @Param("notes") String notes, @Param("evidenceName") String evidenceName,
            @Param("evidenceType") String evidenceType, @Param("createdBy") UUID createdBy,
            @Param("createdAt") Instant createdAt, @Param("clientRequestId") UUID clientRequestId,
            @Param("fingerprint") String fingerprint, @Param("assignedTo") UUID assignedTo,
            @Param("dueAt") Instant dueAt, @Param("sourceLocation") String sourceLocation,
            @Param("destinationLocation") String destinationLocation, @Param("goodsCheckId") UUID goodsCheckId,
            @Param("documentId") UUID documentId, @Param("productId") UUID productId);
}

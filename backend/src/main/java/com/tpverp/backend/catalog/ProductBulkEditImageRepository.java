package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductBulkEditImageRepository extends JpaRepository<ProductBulkEditImage, UUID> {

    List<ProductBulkEditImage> findByEdicion_IdOrderByPosicionAsc(UUID editId);

    @Query("""
            select new com.tpverp.backend.catalog.ProductBulkEditImageView(
                image.id,
                image.productId,
                image.posicion,
                image.fileName,
                image.contentType,
                image.size,
                image.sha256)
            from ProductBulkEditImage image
            where image.edicion.id = :editId
            order by image.posicion asc
            """)
    List<ProductBulkEditImageView> findViewsByEditId(@Param("editId") UUID editId);

    Optional<ProductBulkEditImage> findByIdAndEdicion_Id(UUID id, UUID editId);
}

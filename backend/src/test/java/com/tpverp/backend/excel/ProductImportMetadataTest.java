package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductImportMetadataTest {

    @Test
    void lineMetadataNormalizesSupplierReference() {
        var documentId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var metadata = new ProductImportLineMetadata(documentId, productId, " ref-1 ");

        assertThat(metadata.documentId()).isEqualTo(documentId);
        assertThat(metadata.productId()).isEqualTo(productId);
        assertThat(metadata.supplierReference()).isEqualTo("REF-1");
    }

    @Test
    void blankSupplierReferenceIsStoredAsNull() {
        var metadata = new ProductImportLineMetadata(UUID.randomUUID(), UUID.randomUUID(), "   ");

        assertThat(metadata.supplierReference()).isNull();
    }

    @Test
    void lineMetadataMapsToImportTable() {
        assertThat(ProductImportLineMetadata.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(ProductImportLineMetadata.class.getAnnotation(Table.class).name())
                .isEqualTo("producto_importacion_excel_linea");
    }
}

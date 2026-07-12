package com.tpverp.backend.catalog;

import java.util.UUID;

public record ProductBulkEditImageView(
        UUID id,
        UUID productId,
        int position,
        String fileName,
        String contentType,
        long size,
        String sha256) {

    public static ProductBulkEditImageView from(ProductBulkEditImage image) {
        return new ProductBulkEditImageView(
                image.getId(),
                image.getProductId(),
                image.getPosition(),
                image.getFileName(),
                image.getContentType(),
                image.getSize(),
                image.getSha256());
    }
}

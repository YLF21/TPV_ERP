package com.tpverp.backend.catalog;

import java.util.List;

public record ProductBulkEditImageSyncView(
        long version,
        List<ProductBulkEditImageView> images) {

    public ProductBulkEditImageSyncView {
        images = List.copyOf(images);
    }
}

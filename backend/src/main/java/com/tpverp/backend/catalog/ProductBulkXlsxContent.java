package com.tpverp.backend.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ProductBulkXlsxContent(
        @NotNull @Valid List<ProductBulkEditContent.Row> content,
        HeaderLanguage language) {

    public ProductBulkXlsxContent {
        content = ProductBulkEditContent.validateAndCopy(content);
        language = language == null ? HeaderLanguage.ES : language;
    }

    public ProductBulkXlsxContent(List<ProductBulkEditContent.Row> content) {
        this(content, HeaderLanguage.ES);
    }

    public enum HeaderLanguage {
        ES,
        EN
    }
}

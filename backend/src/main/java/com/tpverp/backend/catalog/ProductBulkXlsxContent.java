package com.tpverp.backend.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record ProductBulkXlsxContent(
        @NotNull @Valid List<ProductBulkEditContent.Row> content,
        HeaderLanguage language,
        Map<String, String> familyCodes,
        Map<String, String> subfamilyCodes) {

    private static final Pattern FAMILY_CODE = Pattern.compile("\\d{3}");
    private static final Pattern SUBFAMILY_CODE = Pattern.compile("\\d{6}");

    public ProductBulkXlsxContent {
        content = ProductBulkEditContent.validateAndCopy(content);
        language = language == null ? HeaderLanguage.ES : language;
        familyCodes = validatedCodes(familyCodes, FAMILY_CODE, "familyCodes");
        subfamilyCodes = validatedCodes(subfamilyCodes, SUBFAMILY_CODE, "subfamilyCodes");
    }

    public ProductBulkXlsxContent(List<ProductBulkEditContent.Row> content) {
        this(content, HeaderLanguage.ES, Map.of(), Map.of());
    }

    public ProductBulkXlsxContent(List<ProductBulkEditContent.Row> content, HeaderLanguage language) {
        this(content, language, Map.of(), Map.of());
    }

    private static Map<String, String> validatedCodes(
            Map<String, String> values, Pattern pattern, String field) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > 5_000) {
            throw new IllegalArgumentException(field + " no puede superar 5000 elementos");
        }
        values.forEach((id, code) -> {
            try {
                java.util.UUID.fromString(id);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(field + " contiene un UUID no valido", exception);
            }
            if (code == null || !pattern.matcher(code.trim()).matches()) {
                throw new IllegalArgumentException(field + " contiene un codigo no valido");
            }
        });
        return Map.copyOf(values);
    }

    public enum HeaderLanguage {
        ES,
        EN
    }
}

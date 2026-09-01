package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV237ContractTest {

    @Test
    void addsNormalizedTrigramAndPrefixIndexesForHierarchySearch() throws IOException {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V237__indexed_family_hierarchy_search.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("create extension if not exists pg_trgm")
                .contains("ix_familia_search_nombre_normalizado_trgm")
                .contains("ix_familia_search_prefijo")
                .contains("ix_familia_search_codigo_prefijo")
                .contains("ix_subfamilia_search_nombre_normalizado_trgm")
                .contains("ix_subfamilia_search_prefijo")
                .contains("ix_subfamilia_search_codigo_prefijo")
                .contains("text_pattern_ops")
                .contains("gin_trgm_ops")
                .contains("tpv_catalog_search_normalize")
                .contains("normalize(upper(value), nfd)");
    }
}

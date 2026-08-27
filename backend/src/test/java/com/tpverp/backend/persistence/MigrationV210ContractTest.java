package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MigrationV210ContractTest {

    private static final Pattern INDEX_NAME = Pattern.compile(
            "create index ([a-z0-9_]+)");

    @Test
    void addsOnlyTenantOrderedReadIndexesForFiscalManagementQueries() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V210__fiscal_read_indexes.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("--[^\\r\\n]*", "")
                    .replaceAll("\\s+", " ")
                    .trim();

            assertThat(sql)
                    .contains("create index ix_registro_fiscal_tenant_tienda on registro_fiscal(")
                    .contains("empresa_id, tienda_id, instalacion_id, generado_en desc, secuencia desc, id desc")
                    .contains("create index ix_exportacion_fiscal_tenant_fecha on exportacion_fiscal(")
                    .contains("empresa_id, instalacion_id, exportada_en desc, id desc")
                    .contains("create index ix_requerimiento_fiscal_tenant_fecha on requerimiento_fiscal(")
                    .contains("empresa_id, instalacion_id, solicitado_en desc, id desc")
                    .doesNotContain("alter table")
                    .doesNotContain("insert into")
                    .doesNotContain("update ")
                    .doesNotContain("delete from")
                    .doesNotContain("drop ");

            var names = INDEX_NAME.matcher(sql);
            var count = 0;
            while (names.find()) {
                count++;
                assertThat(names.group(1).length())
                        .as("PostgreSQL index name length")
                        .isLessThanOrEqualTo(63);
            }
            assertThat(count).isEqualTo(3);
        }
    }
}

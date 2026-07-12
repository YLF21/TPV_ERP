package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV54ContractTest {

    private static final String MIGRATION =
            "db/migration/V54__retirar_descuento_socio.sql";

    @Test
    void convertsLegacyMemberDiscountToTheCurrentMemberPriceMode() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("update producto")
                .contains("set discount_type = 'member_price'")
                .contains("price_use_mode = 'member_price'")
                .contains("where discount_type = 'member_discount'");
    }

    private String migrationSql() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}

package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV180ContractTest {

    @Test
    void allowsIncomingDeliveryAndInvoiceCounterPrefixes() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V180__contadores_documentos_entrada.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql)
                    .contains("contador_documento_tipo_check")
                    .contains("'ae'")
                    .contains("'fe'");
        }
    }
}

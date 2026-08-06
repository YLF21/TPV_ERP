package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AtomicExchangeMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V134__cambio_con_devolucion_atomico.sql";

    @Test
    void installsTheInternalCompensationMethodAndFiscalLinks() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();
            assertThat(sql)
                    .contains("'compensa'")
                    .contains("'exchange'")
                    .contains("'compensacion_devolucion'")
                    .contains("where not exists");
        }
    }
}

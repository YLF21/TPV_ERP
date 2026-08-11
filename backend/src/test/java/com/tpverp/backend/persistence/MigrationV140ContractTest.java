package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV140ContractTest {

    @Test
    void createsStoreScopedVersionedLogoAndSeparateDocumentObservations() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V140__configuracion_documentos_impresos_por_tienda.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("create table logo_documento_tienda");
            assertThat(sql).contains("tienda_id uuid not null references tienda(id)");
            assertThat(sql).contains("contenido bytea not null");
            assertThat(sql).contains("create table configuracion_documento_impreso_tienda");
            assertThat(sql).contains("observaciones_ticket varchar(2000)");
            assertThat(sql).contains("observaciones_factura varchar(2000)");
            assertThat(sql).contains("observaciones_albaran varchar(2000)");
            assertThat(sql).contains("foreign key (logo_id, tienda_id)");
        }
    }
}

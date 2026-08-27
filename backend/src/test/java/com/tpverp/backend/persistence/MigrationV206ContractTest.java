package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MigrationV206ContractTest {

    @Test
    void inventoriesLegacyIdentityAndSeparatesCancellationFromInvoiceQr() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V206__legacy_fiscal_identity_and_cancellation_qr.sql")) {
            assertThat(input).isNotNull();
            var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);

            assertThat(sql)
                    .contains("inventario_identidad_legacy_fiscal")
                    .contains("identidad_legacy_artefacto_fiscal")
                    .contains("legacy_unresolved")
                    .contains("registro_alta_xml")
                    .contains("alta_relacionada")
                    .contains("alter column qr_url drop not null")
                    .contains("registroanulacion no puede contener qr de factura")
                    .contains("solo registroalta puede tener snapshot de impresion fiscal")
                    .doesNotContain("update artefacto_registro_fiscal");
        }
    }
}

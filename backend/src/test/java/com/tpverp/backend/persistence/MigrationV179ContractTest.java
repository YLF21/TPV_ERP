package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV179ContractTest {

    @Test
    void ampliaEntradasValoradasSinIntroducirSemanticaFiscal() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V179__documentos_entrada_valorados.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("tipo_documento varchar(32) not null default 'ENTRADA_ALMACEN'")
                    .contains("'ENTRADA_ALMACEN', 'ALBARAN_ENTRADA', 'FACTURA_ENTRADA'")
                    .contains("fuente_precio varchar(16) not null default 'PURCHASE'")
                    .contains("'PURCHASE', 'SALE', 'MEMBER', 'WHOLESALE', 'OFFER'")
                    .contains("descuento_global numeric(5,2) not null default 0")
                    .contains("alter column cantidad type numeric(19,3)")
                    .contains("descuento numeric(5,2) not null default 0")
                    .contains("precio_personalizado boolean not null default false")
                    .contains("create table entrada_almacen_albaran_origen")
                    .contains("unique (albaran_id)")
                    .contains("ix_entrada_almacen_tienda_tipo_fecha")
                    .contains("ix_entrada_almacen_proveedor")
                    .contains("'ALBARAN_ENTRADA', 'FACTURA_ENTRADA'")
                    .doesNotContain("verifactu", "cuenta_cobrar", "pago", "asiento_contable");
        }
    }
}

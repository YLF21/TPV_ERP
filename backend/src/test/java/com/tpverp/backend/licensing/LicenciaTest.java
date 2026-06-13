package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.installation.Instalacion;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LicenciaTest {

    private static final Map<String, String> DIRECCION = Map.of(
            "linea1", "Calle Mayor 1",
            "ciudad", "Las Palmas",
            "codigoPostal", "35001",
            "provincia", "Las Palmas",
            "pais", "ES");

    @Test
    void conservaElRegimenFiscalFirmado() {
        var empresa = new Empresa("B12345678", "Empresa", DIRECCION);
        var tienda = new Tienda(
                empresa, "Tienda", DIRECCION, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var instalacion = new Instalacion("INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z"));

        var licencia = new Licencia(
                tienda,
                instalacion,
                "LIC-1",
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2027-06-08T00:00:00Z"),
                1,
                0,
                TaxRegime.IGIC,
                "{}",
                "hash",
                2,
                Instant.parse("2026-06-08T00:00:00Z"),
                Map.of(),
                ResultadoImportacion.ACEPTADA,
                null,
                true);

        assertThat(licencia.getRegimenImpuesto()).isEqualTo(TaxRegime.IGIC);
    }
}

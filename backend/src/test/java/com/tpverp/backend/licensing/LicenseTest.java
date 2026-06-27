package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LicenseTest {

    private static final Map<String, String> DIRECCION = Map.of(
            "linea1", "Calle Mayor 1",
            "ciudad", "Las Palmas",
            "codigoPostal", "35001",
            "provincia", "Las Palmas",
            "pais", "ES");

    @Test
    void conservaElRegimenFiscalFirmado() {
        var empresa = new Company("B12345678", "Company", DIRECCION);
        var tienda = new Store(
                empresa, "Store", DIRECCION, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var instalacion = new Installation("INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z"));

        var licencia = new License(
                tienda,
                instalacion,
                "LIC-1",
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2027-06-08T00:00:00Z"),
                1,
                0,
                "B12345678",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "{}",
                "hash",
                3,
                Instant.parse("2026-06-08T00:00:00Z"),
                Map.of(),
                ImportResult.ACEPTADA,
                null,
                true);

        assertThat(licencia.getRegimenImpuesto()).isEqualTo(TaxRegime.IGIC);
        assertThat(licencia.getTaxId()).isEqualTo("B12345678");
        assertThat(licencia.getTaxpayerType()).isEqualTo(TaxpayerType.SOCIEDAD);
        assertThat(licencia.getFormatVersion()).isEqualTo(3);
    }
}

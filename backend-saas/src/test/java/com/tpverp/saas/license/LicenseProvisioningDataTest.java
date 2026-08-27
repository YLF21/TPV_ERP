package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LicenseProvisioningDataTest {

    @Test
    void normalizaPaisIsoAlfa2YValidaCodigoPostalEspanol() {
        var normalized = LicenseProvisioningData.fiscalAddress(
                address("es", "35001"), "companyAddress");

        assertThat(normalized.get("pais")).isEqualTo("ES");
        assertThat(normalized.get("codigoPostal")).isEqualTo("35001");
    }

    @Test
    void rechazaPaisQueNoSeaIsoAlfa2() {
        assertThatThrownBy(() -> LicenseProvisioningData.fiscalAddress(
                address("ESP", "35001"), "storeAddress"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO alfa-2");
    }

    @Test
    void rechazaCodigoPostalEspanolQueNoTengaCincoDigitos() {
        assertThatThrownBy(() -> LicenseProvisioningData.fiscalAddress(
                address("ES", "3500A"), "companyAddress"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 digitos");
    }

    private static Map<String, String> address(String country, String postalCode) {
        return Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", postalCode,
                "provincia", "Las Palmas",
                "pais", country);
    }
}

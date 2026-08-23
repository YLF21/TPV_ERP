package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VerifactuEndpointResolverTest {

    @Test
    void resuelveLosEndpointsOficialesDelWsdl() {
        var resolver = new VerifactuEndpointResolver();

        assertThat(resolver.resolve(VerifactuEndpointMode.PRODUCTION))
                .isEqualTo("https://www1.agenciatributaria.gob.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
        assertThat(resolver.resolve(VerifactuEndpointMode.PRODUCTION_SEAL))
                .isEqualTo("https://www10.agenciatributaria.gob.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
        assertThat(resolver.resolve(VerifactuEndpointMode.TEST))
                .isEqualTo("https://prewww1.aeat.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
        assertThat(resolver.resolve(VerifactuEndpointMode.TEST_SEAL))
                .isEqualTo("https://prewww10.aeat.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
    }

    @Test
    void rechazaEndpointsFueraDelCatalogoOficial() {
        var resolver = new VerifactuEndpointResolver();

        assertThatThrownBy(() -> resolver.requireOfficial("http://localhost:8080/verifactu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalogo oficial");
    }
}

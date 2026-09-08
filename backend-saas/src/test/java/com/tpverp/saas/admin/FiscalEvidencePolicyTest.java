package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FiscalEvidencePolicyTest {

    @Test
    void aceptaEvidenciaConcretaYLaNormaliza() {
        assertThat(FiscalEvidencePolicy.require(
                "  Operacion exenta por entrega intracomunitaria  ", "Motivo", 10))
                .isEqualTo("Operacion exenta por entrega intracomunitaria");
    }

    @Test
    void rechazaCamposVaciosCortosYMarcadoresGenericos() {
        for (String value : new String[] {null, "", "test", "N/A", "no aplica", "dummy",
                "aaaaaaaaaaaa", "test test test", "abcdabcdabcd", "pendiente pendiente",
                "no aplica porque no aplica", "esto es una prueba"}) {
            assertThatThrownBy(() -> FiscalEvidencePolicy.require(value, "Evidencia", 8))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400 BAD_REQUEST");
        }
    }
}

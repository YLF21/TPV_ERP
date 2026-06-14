package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FiscalJsonHasherTest {

    private final FiscalJsonHasher hasher = new FiscalJsonHasher(new ObjectMapper());

    @Test
    void ignoraElOrdenDeInsercionDeLasClaves() {
        var first = new LinkedHashMap<String, Object>();
        first.put("numero", "001");
        first.put("total", new BigDecimal("10.00"));
        var second = new LinkedHashMap<String, Object>();
        second.put("total", new BigDecimal("10.00"));
        second.put("numero", "001");

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
    }

    @Test
    void cambiaCuandoCambiaUnDatoFiscal() {
        assertThat(hasher.hash(Map.of("total", "10.00")))
                .isNotEqualTo(hasher.hash(Map.of("total", "10.01")));
    }

    @Test
    void normalizaBigDecimalEnNotacionExponencialARepresentacionPlana() {
        assertThat(hasher.hash(Map.of("total", new BigDecimal("1E+3"))))
                .isEqualTo(hasher.hash(Map.of("total", new BigDecimal("1000"))));
    }

    @Test
    void normalizaEscalasBigDecimalEnMapasYListasAnidados() {
        var first = Map.<String, Object>of(
                "desglose", Map.of("base", new BigDecimal("10.0")),
                "lineas", List.of(Map.of("total", new BigDecimal("20.00"))));
        var second = Map.<String, Object>of(
                "desglose", Map.of("base", new BigDecimal("10.00")),
                "lineas", List.of(Map.of("total", new BigDecimal("20.0"))));

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(second));
    }

    @Test
    void rechazaSnapshotNulo() {
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

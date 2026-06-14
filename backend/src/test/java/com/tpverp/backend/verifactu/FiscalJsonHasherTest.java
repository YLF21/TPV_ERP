package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
}

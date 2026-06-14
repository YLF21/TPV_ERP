package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FiscalJsonHasherTest {

    private final FiscalJsonHasher hasher = new FiscalJsonHasher();

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

    @Test
    void aceptaTiposFiscalesSegurosYCollections() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("nulo", null);
        snapshot.put("texto", "valor");
        snapshot.put("booleano", true);
        snapshot.put("decimal", new BigDecimal("10.00"));
        snapshot.put("enteroGrande", BigInteger.TEN);
        snapshot.put("enteros", List.of((byte) 1, (short) 2, 3, 4L));
        snapshot.put("coleccion", new ArrayDeque<>(List.of("A", "B")));

        assertThat(hasher.hash(snapshot)).hasSize(64);
    }

    @Test
    void rechazaObjetosArbitrarios() {
        assertThatThrownBy(() -> hasher.hash(Map.of("objeto", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.Object");
    }

    @Test
    void rechazaNumerosNoSegurosYClavesNoTextuales() {
        assertThatThrownBy(() -> hasher.hash(Map.of("decimal", 10.5D)))
                .isInstanceOf(IllegalArgumentException.class);

        var invalidMap = new LinkedHashMap<Object, Object>();
        invalidMap.put(1, "valor");
        assertThatThrownBy(() -> hasher.hash(Map.of("mapa", invalidMap)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

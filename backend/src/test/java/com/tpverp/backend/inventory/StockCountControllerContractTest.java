package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class StockCountControllerContractTest {
    @Test
    void exposesProtectedStockCountWorkflow() {
        assertThat(StockCountController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/stock-counts");
        assertThat(StockCountController.class.getAnnotation(PreAuthorize.class).value())
                .contains("ADMIN", "GESTION_ALMACEN");
        var mappings = Arrays.stream(StockCountController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(Method::getName).toList();
        assertThat(mappings).contains("create", "confirm", "cancel");
    }
}

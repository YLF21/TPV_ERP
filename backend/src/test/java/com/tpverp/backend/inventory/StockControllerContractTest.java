package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class StockControllerContractTest {

    @Test
    void exposesStockApiWithMethodSecurity() {
        assertThat(StockController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/stock");
        assertThat(Arrays.stream(StockController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .hasSizeGreaterThanOrEqualTo(3);
    }
}

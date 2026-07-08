package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

class StockControllerContractTest {

    @Test
    void exposesStockApiWithMethodSecurity() {
        assertThat(StockController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/stock");
        assertThat(Arrays.stream(StockController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void exposesSnapshotRebuildEndpoint() throws NoSuchMethodException {
        var method = StockController.class.getDeclaredMethod("rebuildSnapshots");

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/snapshots/rebuild");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("hasRole('ADMIN')");
    }

    @Test
    void exposesTopSalesEndpoint() throws NoSuchMethodException {
        var method = StockController.class.getDeclaredMethod(
                "topSales", String.class, LocalDate.class, LocalDate.class, LocalDate.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/top-sales");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("hasRole('ADMIN')");
        assertThat(Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(RequestParam.class)))
                .hasSize(4);
    }
}

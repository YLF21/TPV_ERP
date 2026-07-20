package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
                "topSales", String.class, LocalDate.class, LocalDate.class, LocalDate.class, UUID.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/top-sales");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("hasRole('ADMIN')");
        assertThat(Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(RequestParam.class)))
                .hasSize(5);
    }

    @Test
    void exposesPagedStockEndpoint() throws NoSuchMethodException {
        var method = StockController.class.getDeclaredMethod(
                "page", Integer.class, String.class, String.class, String.class, String.class, String.class,
                UUID.class, UUID.class, Boolean.class, Authentication.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/page");
        assertThat(method.getGenericReturnType().getTypeName())
                .contains("PagedResult", "StockPageItem");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("STOCK_READ", "GESTION_PRODUCTO", "GESTION_ALMACEN", "GESTION_VENTAS", "VENTA", "hasRole('ADMIN')");
        assertThat(Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(RequestParam.class)))
                .hasSize(9);
    }

    @Test
    void exposesProductSalesHistoryWithOptionalDateRange() throws NoSuchMethodException {
        var method = StockController.class.getDeclaredMethod(
                "salesHistory", UUID.class, LocalDate.class, LocalDate.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/products/{productId}/sales-history");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("STOCK_READ", "GESTION_PRODUCTO", "hasRole('ADMIN')");
        assertThat(Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(PathVariable.class)))
                .hasSize(1);
        assertThat(Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(RequestParam.class)))
                .hasSize(2);
    }

    @Test
    void exposesSettingsAndMinimumEndpointsWithSeparatedPermissions() throws NoSuchMethodException {
        var getSettings = StockController.class.getDeclaredMethod("settings");
        var putSettings = StockController.class.getDeclaredMethod(
                "updateSettings", StockSettingsCommand.class);
        var patchInactiveSales = StockController.class.getDeclaredMethod(
                "updateInactiveProductSales", InactiveProductSalesCommand.class);
        var getMinimum = StockController.class.getDeclaredMethod(
                "minimum", UUID.class, UUID.class);
        var putMinimum = StockController.class.getDeclaredMethod(
                "updateMinimum", UUID.class, UUID.class, StockMinimumCommand.class);
        var deleteMinimum = StockController.class.getDeclaredMethod(
                "deleteMinimum", UUID.class, UUID.class);

        assertThat(getSettings.getAnnotation(GetMapping.class).value())
                .containsExactly("/settings");
        assertThat(getSettings.getAnnotation(PreAuthorize.class).value())
                .contains("STOCK_READ", "GESTION_PRODUCTO", "GESTION_ALMACEN", "WAREHOUSES_MANAGE", "hasRole('ADMIN')");
        assertThat(putSettings.getAnnotation(PutMapping.class).value())
                .containsExactly("/settings");
        assertThat(putSettings.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSES_MANAGE", "GESTION_ALMACEN", "hasRole('ADMIN')")
                .doesNotContain("GESTION_PRODUCTO");
        assertThat(patchInactiveSales.getAnnotation(PatchMapping.class).value())
                .containsExactly("/settings/inactive-product-sales");
        assertThat(patchInactiveSales.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_PRODUCTO", "hasRole('ADMIN')")
                .doesNotContain("WAREHOUSES_MANAGE");
        assertThat(getMinimum.getAnnotation(GetMapping.class).value())
                .containsExactly("/minimums/{productId}/{warehouseId}");
        assertThat(getMinimum.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSES_MANAGE");
        assertThat(putMinimum.getAnnotation(PutMapping.class).value())
                .containsExactly("/minimums/{productId}/{warehouseId}");
        assertThat(deleteMinimum.getAnnotation(DeleteMapping.class).value())
                .containsExactly("/minimums/{productId}/{warehouseId}");
        assertThat(putMinimum.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSES_MANAGE", "GESTION_ALMACEN");
        assertThat(deleteMinimum.getAnnotation(PreAuthorize.class).value())
                .contains("WAREHOUSES_MANAGE", "GESTION_ALMACEN");
    }

    @Test
    void warehouseManagementCanAdjustAndTransferStockWithoutTechnicalRebuild() throws NoSuchMethodException {
        assertThat(StockController.class.getDeclaredMethod(
                        "adjust", StockController.AdjustmentRequest.class, Authentication.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("STOCK_ADJUST", "GESTION_ALMACEN");
        assertThat(StockController.class.getDeclaredMethod(
                        "transfer", StockController.TransferRequest.class, Authentication.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("STOCK_TRANSFER", "GESTION_ALMACEN");
        assertThat(StockController.class.getDeclaredMethod("rebuildSnapshots")
                .getAnnotation(PreAuthorize.class).value())
                .contains("STOCK_ADJUST")
                .doesNotContain("GESTION_ALMACEN");
    }
}

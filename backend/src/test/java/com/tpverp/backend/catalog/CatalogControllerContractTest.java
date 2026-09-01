package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.TAXES_MANAGE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSES_MANAGE;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class CatalogControllerContractTest {

    @Test
    void exposesExpectedApiRootsAndMethodSecurity() {
        assertController(TaxController.class, "/api/v1/taxes");
        assertController(WarehouseController.class, "/api/v1/warehouses");
        assertController(FamilyController.class, "/api/v1/families");
        assertController(ProductController.class, "/api/v1/products");
    }

    @Test
    void salesCanReadProductOptionsButOnlyProductManagementCanWriteProducts() throws Exception {
        assertAllows(FamilyController.class, "list", GetMapping.class, VENTA);
        assertAllows(FamilyController.class, "resolve", GetMapping.class, VENTA, String.class);
        assertAllows(FamilyController.class, "listSubfamilies", GetMapping.class, VENTA, java.util.UUID.class);
        assertAllows(FamilyController.class, "nextCode", GetMapping.class, PRODUCTS_WRITE);
        assertAllows(FamilyController.class, "nextSuffix", GetMapping.class, PRODUCTS_WRITE, java.util.UUID.class);
        assertAllows(TaxController.class, "selectable", GetMapping.class, VENTA);
        assertAllows(TaxController.class, "selectable", GetMapping.class, GESTION_ALMACEN);
        assertAllows(TaxController.class, "list", GetMapping.class, TAXES_MANAGE);
        assertAllows(ProductController.class, "warehouseOptions", GetMapping.class, GESTION_ALMACEN);
        assertAllows(ProductController.class, "create", PostMapping.class, GESTION_PRODUCTO, CatalogService.ProductRequest.class);
        assertAllows(ProductController.class, "uploadImage", PutMapping.class, GESTION_PRODUCTO,
                java.util.UUID.class, org.springframework.web.multipart.MultipartFile.class,
                org.springframework.security.core.Authentication.class);

        assertThat(ProductController.class.getDeclaredMethod(
                        "uploadImage", java.util.UUID.class, org.springframework.web.multipart.MultipartFile.class,
                        org.springframework.security.core.Authentication.class)
                .getAnnotation(PreAuthorize.class).value())
                .doesNotContain("'" + VENTA + "'");
    }

    @Test
    void familyMaintenanceWritesRequireProductManagementAndManualOrderIsGone() throws Exception {
        assertAllows(FamilyController.class, "create", PostMapping.class,
                PRODUCTS_WRITE, FamilyController.FamilyRequest.class);
        assertAllows(FamilyController.class, "rename", PutMapping.class,
                GESTION_PRODUCTO, java.util.UUID.class, FamilyController.FamilyRequest.class);
        assertAllows(FamilyController.class, "delete", DeleteMapping.class,
                PRODUCTS_WRITE, java.util.UUID.class, boolean.class);
        assertAllows(FamilyController.class, "familyDeleteImpact", GetMapping.class,
                GESTION_PRODUCTO, java.util.UUID.class);
        assertThatThrownBy(() -> FamilyController.class.getDeclaredMethod(
                "reorder", List.class)).isInstanceOf(NoSuchMethodException.class);
        assertAllows(FamilyController.class, "createSubfamily", PostMapping.class,
                GESTION_PRODUCTO, java.util.UUID.class, FamilyController.SubfamilyRequest.class);
        assertAllows(FamilyController.class, "renameSubfamily", PutMapping.class,
                PRODUCTS_WRITE, java.util.UUID.class, FamilyController.SubfamilyRequest.class);
        assertAllows(FamilyController.class, "deleteSubfamily", DeleteMapping.class,
                GESTION_PRODUCTO, java.util.UUID.class, boolean.class);
        assertAllows(FamilyController.class, "subfamilyDeleteImpact", GetMapping.class,
                PRODUCTS_WRITE, java.util.UUID.class);
        assertThatThrownBy(() -> FamilyController.class.getDeclaredMethod(
                "reorderSubfamilies", java.util.UUID.class, List.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void familyProductListAndBulkClassificationContractsUseWritePermissions() throws Exception {
        assertAllows(FamilyController.class, "products", GetMapping.class, PRODUCTS_WRITE,
                java.util.UUID.class, java.util.UUID.class, Integer.class,
                String.class, String.class, String.class);
        assertAllows(ProductController.class, "moveClassification", PostMapping.class, PRODUCTS_WRITE,
                CatalogService.BulkMoveRequest.class);
        assertThatThrownBy(() -> ProductController.class.getDeclaredMethod(
                "thumbnail", java.util.UUID.class)).isInstanceOf(NoSuchMethodException.class);
        assertAllows(FamilyController.class, "search", GetMapping.class, PRODUCTS_READ,
                String.class, Integer.class, String.class);
    }

    @Test
    void warehouseStructureCanBeManagedByWarehouseOperationsOrDedicatedManagement() throws Exception {
        assertAllows(WarehouseController.class, "create", PostMapping.class,
                GESTION_ALMACEN, WarehouseController.NameRequest.class);
        assertAllows(WarehouseController.class, "create", PostMapping.class,
                WAREHOUSES_MANAGE, WarehouseController.NameRequest.class);
        assertAllows(WarehouseController.class, "rename", PutMapping.class,
                GESTION_ALMACEN, java.util.UUID.class, WarehouseController.NameRequest.class);
        assertAllows(WarehouseController.class, "setActive", PatchMapping.class,
                GESTION_ALMACEN, java.util.UUID.class, TaxController.ActiveRequest.class);

        var delete = WarehouseController.class.getDeclaredMethod("delete", java.util.UUID.class);
        assertThat(delete.getAnnotation(DeleteMapping.class)).isNotNull();
        assertThat(delete.getAnnotation(PreAuthorize.class).value())
                .contains("'" + WAREHOUSES_MANAGE + "'")
                .doesNotContain("'" + GESTION_ALMACEN + "'");
    }

    private void assertController(Class<?> type, String path) {
        assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly(path);
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .isNotEmpty();
    }

    private void assertAllows(
            Class<?> controller,
            String methodName,
            Class<? extends Annotation> mappingType,
            String permission,
            Class<?>... parameterTypes) throws Exception {
        var method = controller.getDeclaredMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(mappingType)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value()).contains("'" + permission + "'");
    }
}

package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
        assertAllows(FamilyController.class, "listSubfamilies", GetMapping.class, VENTA, java.util.UUID.class);
        assertAllows(TaxController.class, "selectable", GetMapping.class, VENTA);
        assertAllows(TaxController.class, "selectable", GetMapping.class, GESTION_ALMACEN);
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

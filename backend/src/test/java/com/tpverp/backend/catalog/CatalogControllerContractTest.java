package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
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
    void allowsSalesAppToOpenAndSubmitTheProductCreateDialog() throws Exception {
        assertAllowsVenta(FamilyController.class, "list", GetMapping.class);
        assertAllowsVenta(FamilyController.class, "listSubfamilies", GetMapping.class, java.util.UUID.class);
        assertAllowsVenta(TaxController.class, "selectable", GetMapping.class);
        assertAllowsVenta(ProductController.class, "create", PostMapping.class, CatalogService.ProductRequest.class);
        assertAllowsVenta(ProductController.class, "uploadImage", PutMapping.class, java.util.UUID.class, org.springframework.web.multipart.MultipartFile.class);
    }

    private void assertController(Class<?> type, String path) {
        assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly(path);
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .isNotEmpty();
    }

    private void assertAllowsVenta(
            Class<?> controller,
            String methodName,
            Class<? extends Annotation> mappingType,
            Class<?>... parameterTypes) throws Exception {
        var method = controller.getDeclaredMethod(methodName, parameterTypes);

        assertThat(method.getAnnotation(mappingType)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value()).contains("'" + VENTA + "'");
    }
}

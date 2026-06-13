package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class CatalogControllerContractTest {

    @Test
    void exposesExpectedApiRootsAndMethodSecurity() {
        assertController(TaxController.class, "/api/v1/taxes");
        assertController(WarehouseController.class, "/api/v1/warehouses");
        assertController(FamilyController.class, "/api/v1/families");
        assertController(ProductController.class, "/api/v1/products");
    }

    private void assertController(Class<?> type, String path) {
        assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly(path);
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .isNotEmpty();
    }
}

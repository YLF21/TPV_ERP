package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ProductSupplierControllerContractTest {

    @Test
    void exposesProductSupplierEndpointsWithProductPermissions() throws Exception {
        RequestMapping root = ProductSupplierController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/v1/products/{productId}/suppliers");

        assertEndpoint(
                "list",
                PRODUCTS_READ,
                GetMapping.class,
                new String[0],
                UUID.class);
        assertEndpoint(
                "link",
                PRODUCTS_WRITE,
                PostMapping.class,
                new String[0],
                UUID.class,
                ProductSupplierController.LinkRequest.class);
        assertEndpoint(
                "update",
                PRODUCTS_WRITE,
                PutMapping.class,
                new String[] {"/{supplierId}"},
                UUID.class,
                UUID.class,
                ProductSupplierController.ReferenceRequest.class);
        assertEndpoint(
                "unlink",
                PRODUCTS_WRITE,
                DeleteMapping.class,
                new String[] {"/{supplierId}"},
                UUID.class,
                UUID.class);
    }

    @Test
    void requiresSupplierIdOnlyWhenCreatingTheLink() {
        var linkComponents = ProductSupplierController.LinkRequest.class.getRecordComponents();
        var referenceComponents =
                ProductSupplierController.ReferenceRequest.class.getRecordComponents();

        assertThat(linkComponents).extracting(component -> component.getName())
                .containsExactly("supplierId", "supplierReference");
        assertThat(linkComponents[0].getAccessor().isAnnotationPresent(NotNull.class)).isTrue();
        assertThat(referenceComponents).extracting(component -> component.getName())
                .containsExactly("supplierReference");
    }

    private void assertEndpoint(
            String methodName,
            String permission,
            Class<? extends Annotation> mappingType,
            String[] paths,
            Class<?>... parameterTypes)
            throws Exception {
        Method method = ProductSupplierController.class.getDeclaredMethod(methodName, parameterTypes);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
                .contains("hasRole('ADMIN')")
                .contains("hasAuthority('" + permission + "')");
        assertThat(mappingPaths(method, mappingType)).containsExactly(paths);
    }

    private String[] mappingPaths(
            Method method, Class<? extends Annotation> mappingType) throws Exception {
        Annotation mapping = method.getAnnotation(mappingType);
        assertThat(mapping).isNotNull();
        return (String[]) mappingType.getMethod("value").invoke(mapping);
    }
}

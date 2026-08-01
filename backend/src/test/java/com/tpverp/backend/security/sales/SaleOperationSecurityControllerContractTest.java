package com.tpverp.backend.security.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class SaleOperationSecurityControllerContractTest {

    @Test
    void exposesTheStableSalesEndpointAndKeepsWritesAdminOnly()
            throws NoSuchMethodException {
        var mapping = SaleOperationSecurityController.class
                .getAnnotation(RequestMapping.class);
        assertThat(mapping.value())
                .containsExactly("/api/v1/sales/operation-security");

        assertThat(permission("current"))
                .contains("'VENTA'")
                .contains("hasRole('ADMIN')");
        assertThat(permission(
                "update",
                SaleOperationSecurityController.UpdateRequest.class))
                .isEqualTo("hasRole('ADMIN')");
        assertThat(permission(
                "reset",
                SaleOperationSecurityController.ResetRequest.class))
                .isEqualTo("hasRole('ADMIN')");
    }

    private String permission(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = SaleOperationSecurityController.class
                .getMethod(methodName, parameterTypes);
        return method.getAnnotation(PreAuthorize.class).value();
    }
}

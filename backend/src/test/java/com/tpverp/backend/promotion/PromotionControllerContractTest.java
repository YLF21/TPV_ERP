package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

class PromotionControllerContractTest {

    private static final String READ_PERMISSION =
            "hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','STOCK_READ')";
    private static final String MANAGE_PERMISSION = "hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')";
    private static final String SALES_PERMISSION =
            "hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')";

    @Test
    void exposesPromotionManagementApiWithSalesManagementPermission() throws Exception {
        assertThat(PromotionController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/promotions");

        assertGet("list");
        assertPost("create", "");
        assertPost("duplicate", "/{id}/duplicate");
        assertPost("activate", "/{id}/activate");
        assertPost("deactivate", "/{id}/deactivate");
        assertDelete("delete", "/{id}");
    }

    @Test
    void exposesSalePreviewApiWithSalesPermission() throws Exception {
        assertPost("preview", "/preview", SALES_PERMISSION);
    }

    @Test
    void createValidatesRequestBody() {
        var requestParameter = method("create").getParameters()[0];

        assertThat(requestParameter.getAnnotation(RequestBody.class)).isNotNull();
        assertThat(requestParameter.getAnnotation(Valid.class)).isNotNull();
    }

    private void assertGet(String methodName) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(READ_PERMISSION);
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
    }

    private void assertPost(String methodName, String path) throws Exception {
        assertPost(methodName, path, MANAGE_PERMISSION);
    }

    private void assertPost(String methodName, String path, String permission) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(permission);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    }

    private void assertDelete(String methodName, String path) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(MANAGE_PERMISSION);
        assertThat(method.getAnnotation(DeleteMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private Method method(String name) {
        return java.util.Arrays.stream(PromotionController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}

package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class PromotionControllerContractTest {

    private static final String PERMISSION = "hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')";

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

    private void assertGet(String methodName) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(PERMISSION);
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
    }

    private void assertPost(String methodName, String path) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(PERMISSION);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    }

    private void assertDelete(String methodName, String path) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(PERMISSION);
        assertThat(method.getAnnotation(DeleteMapping.class).value()).containsExactly(path);
    }

    private Method method(String name) {
        return java.util.Arrays.stream(PromotionController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}

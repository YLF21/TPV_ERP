package com.tpverp.backend.goodscheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class GoodsCheckControllerContractTest {

    private static final String PERMISSION = "hasRole('ADMIN') or hasAuthority('GESTION_ALMACEN')";

    @Test
    void exposesGoodsCheckEndpointsWithDocumentPermissions() throws Exception {
        assertThat(GoodsCheckController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/goods-checks");

        assertPost("start", "/documents/{documentId}/start");
        assertPost("importDocument", "/documents/{documentId}/import");
        assertGet("get", "/{id}");
        assertPost("scan", "/{id}/scan");
        assertPost("close", "/{id}/close");
    }

    private void assertPost(String methodName, String path) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(PERMISSION);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    }

    private void assertGet(String methodName, String path) throws Exception {
        var method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(PERMISSION);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    }

    private Method method(String name) {
        return java.util.Arrays.stream(GoodsCheckController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}

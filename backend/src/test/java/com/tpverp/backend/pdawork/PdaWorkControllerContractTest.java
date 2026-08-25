package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class PdaWorkControllerContractTest {
    @Test
    void exposesProtectedOperationalWorkflow() {
        assertThat(PdaWorkController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/v1/pda-work");
        assertThat(PdaWorkController.class.getAnnotation(PreAuthorize.class).value())
                .contains("ADMIN", "GESTION_ALMACEN", "STOCK_ADJUST", "STOCK_TRANSFER");
        var posts=Arrays.stream(PdaWorkController.class.getDeclaredMethods())
                .filter(method->method.isAnnotationPresent(PostMapping.class)).map(Method::getName).toList();
        assertThat(posts).contains("create", "finish", "cancel");
    }
}
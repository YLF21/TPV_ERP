package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ControlControllerContractTest {

    @Test
    void exposesSecuredGestionEndpointsWithoutDelete() throws Exception {
        assertThat(ControlRuleController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/control/rules");
        assertThat(ControlAlertController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/control/alerts");
        assertThat(ControlRuleController.class.getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS", "CONTROL_RULES_MANAGE");
        assertThat(ControlAlertController.class.getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS", "CONTROL_ALERTS_READ", "CONTROL_ALERTS_MANAGE");
        assertThat(Arrays.stream(ControlRuleController.class.getDeclaredMethods()))
                .noneMatch(method -> method.isAnnotationPresent(DeleteMapping.class));
        assertThat(Arrays.stream(ControlAlertController.class.getDeclaredMethods()))
                .noneMatch(method -> method.isAnnotationPresent(DeleteMapping.class));

        Method document = ControlAlertController.class.getDeclaredMethod("relatedDocument", UUID.class);
        assertThat(document.getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS", "CONTROL_ALERTS_READ", "GESTION_VENTAS");

        Method summary = ControlAlertController.class.getDeclaredMethod("summary");
        assertThat(summary.getAnnotation(GetMapping.class).value()).containsExactly("/summary");

        Method groups = ControlAlertController.class.getDeclaredMethod(
                "groups", java.time.Instant.class, java.time.Instant.class);
        assertThat(groups.getAnnotation(GetMapping.class).value()).containsExactly("/groups");

        Method catalog = ControlRuleController.class.getDeclaredMethod("catalog");
        assertThat(catalog.getAnnotation(GetMapping.class).value()).containsExactly("/catalog");
    }
}

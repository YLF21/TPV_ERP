package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class PdaOperationalObservabilityControllerContractTest {
    @Test
    void exposesSeparateManagementReadAndPdaIngestionContracts() throws Exception {
        assertThat(PdaOperationalObservabilityController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/pda-observability");
        var dashboard = PdaOperationalObservabilityController.class.getDeclaredMethod("dashboard",
                int.class, int.class, int.class, int.class, java.util.UUID.class);
        assertThat(dashboard.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(dashboard.getAnnotation(PreAuthorize.class).value()).contains("ADMIN", "GESTION_ALMACEN");
        var record = java.util.Arrays.stream(PdaOperationalObservabilityController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)).findFirst().orElseThrow();
        assertThat(record.getAnnotation(PostMapping.class).value()).containsExactly("/events");
        assertThat(record.getAnnotation(PreAuthorize.class).value()).contains("PDA", "STOCK_READ", "GESTION_ALMACEN");
    }

    @Test
    void ingestionContractBoundsIdentifiersAndDuration() {
        RecordComponent[] fields = PdaOperationalObservabilityController.EventRequest.class.getRecordComponents();
        assertThat(component(fields, "clientEventId").getAccessor().getAnnotation(NotNull.class)).isNotNull();
        assertThat(component(fields, "occurredAt").getAccessor().getAnnotation(NotNull.class)).isNotNull();
        assertThat(component(fields, "durationMs").getAccessor().getAnnotation(Max.class).value()).isEqualTo(120000);
        assertThat(component(fields, "code").getAccessor().getAnnotation(jakarta.validation.constraints.Size.class).max())
                .isEqualTo(64);
    }

    private static RecordComponent component(RecordComponent[] fields, String name) {
        return java.util.Arrays.stream(fields).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
    }
}

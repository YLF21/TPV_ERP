package com.tpverp.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class AuditControllerContractTest {

    @Test
    void exposesAuditAsReadOnlyResource() throws NoSuchMethodException {
        assertThat(AuditController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/audit");
        assertThat(AuditController.class.getMethod(
                        "query", java.time.Instant.class, java.time.Instant.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("ADMIN", "AUDIT_READ");
        assertThat(Arrays.stream(AuditController.class.getDeclaredMethods()))
                .noneMatch(method -> method.isAnnotationPresent(DeleteMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class));
    }
}

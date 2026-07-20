package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class DocumentOperationalTimelineControllerContractTest {

    @Test
    void exposesReadOnlyTimelineWithManagementPermissions() throws Exception {
        assertThat(DocumentOperationalTimelineController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/documents");
        var method = DocumentOperationalTimelineController.class.getDeclaredMethod(
                "timeline", UUID.class, Authentication.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/{documentId}/operational-events");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS", "GESTION_VENTAS", "GESTION_PRODUCTO", "GESTION_ALMACEN", "GESTION_CUENTAS")
                .doesNotContain("'VENTA'");
    }
}

package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class VerifactuControllerContractTest {

    @Test
    void exposesDefectiveRecordsEndpointWithSalesManagementPermission()
            throws NoSuchMethodException {
        assertThat(DefectiveFiscalRecordController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/defective-records");

        var method = DefectiveFiscalRecordController.class.getDeclaredMethod("list");
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
        assertThat(Arrays.stream(DefectiveFiscalRecordController.class.getDeclaredMethods())
                .filter(Method::isSynthetic)
                .toList()).isEmpty();
    }
}

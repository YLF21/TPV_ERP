package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class ReportVisualizationPreferenceControllerContractTest {

    @Test
    void exposesAuthenticatedSalesReportVisualizationPreferenceApi() {
        assertThat(ReportVisualizationPreferenceController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/sales-reports/visualization-preferences");
        assertThat(Arrays.stream(ReportVisualizationPreferenceController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .hasSizeGreaterThanOrEqualTo(2);
    }
}

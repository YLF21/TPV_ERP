package com.tpverp.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class DashboardControllerContractTest {

    @Test
    void preferenceRequiresAppAccessAndDataEndpointsRequireTheirSourcePermission() throws Exception {
        assertThat(DashboardPreferenceController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/gestion/dashboard/preference");
        assertThat(DashboardPreferenceController.class
                .getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS");

        assertThat(method("salesToday").getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS", "GESTION_VENTAS");
        assertThat(method("topProducts").getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS", "GESTION_VENTAS");
        assertThat(method("activePromotions").getAnnotation(PreAuthorize.class).value())
                .contains("APP_GESTION_ACCESS", "GESTION_PRODUCTO");
    }

    @Test
    void percentageComparisonDoesNotInventAValueWithoutPreviousSales() {
        assertThat(GestionDashboardDataController.percentageChange(
                new java.math.BigDecimal("120.00"),
                new java.math.BigDecimal("100.00")))
                .isEqualByComparingTo("20.00");
        assertThat(GestionDashboardDataController.percentageChange(
                new java.math.BigDecimal("120.00"),
                java.math.BigDecimal.ZERO))
                .isNull();
    }

    private static Method method(String name) throws NoSuchMethodException {
        return GestionDashboardDataController.class.getMethod(name);
    }
}

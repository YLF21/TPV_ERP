package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;

class TerminalPaymentConfigurationControllerContractTest {

    @Test
    void exposesCurrentTerminalPaymentConfigurationApi() throws NoSuchMethodException {
        assertThat(TerminalPaymentConfigurationController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/terminal-configuration/payment");

        Method current = TerminalPaymentConfigurationController.class.getDeclaredMethod("current");
        assertThat(current.getParameterCount()).isZero();
        assertThat(current.getReturnType()).isEqualTo(TerminalPaymentConfigurationView.class);
        assertThat(current.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(current.getAnnotation(PreAuthorize.class).value())
                .contains("VENTA");

        Method update = TerminalPaymentConfigurationController.class.getDeclaredMethod(
                "update", TerminalPaymentConfigurationController.UpdateRequest.class);
        assertThat(update.getAnnotation(PatchMapping.class)).isNotNull();
        assertThat(update.getReturnType()).isEqualTo(TerminalPaymentConfigurationView.class);
        assertThat(update.getParameterTypes())
                .containsExactly(TerminalPaymentConfigurationController.UpdateRequest.class);
        assertThat(update.getParameters()[0].getAnnotation(RequestBody.class)).isNotNull();
        assertThat(update.getAnnotation(PreAuthorize.class).value())
                .contains("CONFIGURACION_TERMINAL");

        Method connectionTest = TerminalPaymentConfigurationController.class.getDeclaredMethod("testConnection");
        assertThat(connectionTest.getAnnotation(PostMapping.class).value()).containsExactly("/connection-test");
        assertThat(connectionTest.getReturnType()).isEqualTo(TerminalPaymentConfigurationView.class);
        assertThat(connectionTest.getParameterCount()).isZero();
        assertThat(connectionTest.getAnnotation(PreAuthorize.class).value())
                .contains("CONFIGURACION_TERMINAL");
    }
}

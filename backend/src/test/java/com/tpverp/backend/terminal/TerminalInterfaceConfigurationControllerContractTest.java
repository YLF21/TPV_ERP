package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

class TerminalInterfaceConfigurationControllerContractTest {

    @Test
    void exposesCurrentTerminalInterfaceConfigurationApi() throws NoSuchMethodException {
        assertThat(TerminalInterfaceConfigurationController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/terminal-configuration/interface");

        Method current = TerminalInterfaceConfigurationController.class.getDeclaredMethod("current");
        assertThat(current.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(current.getAnnotation(PreAuthorize.class).value()).contains("VENTA");

        Method update = TerminalInterfaceConfigurationController.class.getDeclaredMethod(
                "update", TerminalInterfaceConfigurationController.UpdateRequest.class);
        assertThat(update.getAnnotation(PatchMapping.class)).isNotNull();
        assertThat(update.getParameters()[0].getAnnotation(RequestBody.class)).isNotNull();
        assertThat(update.getAnnotation(PreAuthorize.class).value())
                .contains("CONFIGURACION_TERMINAL");
    }
}

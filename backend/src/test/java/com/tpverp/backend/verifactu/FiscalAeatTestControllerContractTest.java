package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class FiscalAeatTestControllerContractTest {

    @Test
    void exposesOnlyAdminScopedManualDispatch() throws NoSuchMethodException {
        assertThat(FiscalAeatTestController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/dev/fiscal-aeat-test");
        Method method = FiscalAeatTestController.class.getDeclaredMethod(
                "dispatchNext", FiscalAeatTestDispatchRequest.class);
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/dispatch-next");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
    }
}

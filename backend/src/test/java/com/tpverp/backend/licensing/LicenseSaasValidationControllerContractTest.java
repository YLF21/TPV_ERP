package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class LicenseSaasValidationControllerContractTest {

    @Test
    void exponeEndpointDeValidacionParaTiendas() throws NoSuchMethodException {
        assertThat(LicenseSaasValidationController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/license");
        var method = LicenseSaasValidationController.class.getDeclaredMethod(
                "validate", LicenseSaasValidationRequest.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/validate");
    }
}

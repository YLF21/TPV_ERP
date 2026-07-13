package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class PosCardControllerContractTest {

    @Test
    void exposesProtectedQuoteAndChargeEndpoints() throws Exception {
        assertThat(PosCardController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/pos/card");

        Method quote = PosCardController.class.getDeclaredMethod(
                "quote", PosCashController.SaleRequest.class,
                org.springframework.security.core.Authentication.class);
        assertThat(quote.getAnnotation(PostMapping.class).value()).containsExactly("/quote");
        assertThat(quote.getAnnotation(PreAuthorize.class)).isNotNull();

        Method charge = PosCardController.class.getDeclaredMethod(
                "charge", PosCardController.CardRequest.class,
                org.springframework.security.core.Authentication.class);
        assertThat(charge.getAnnotation(PostMapping.class).value()).containsExactly("/charge");
        assertThat(charge.getAnnotation(PreAuthorize.class)).isNotNull();
    }
}


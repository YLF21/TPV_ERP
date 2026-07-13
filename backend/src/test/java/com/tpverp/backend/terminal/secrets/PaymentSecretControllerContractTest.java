package com.tpverp.backend.terminal.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

class PaymentSecretControllerContractTest {
    @Test
    void endpointsAreAdministrativeAndProvideNoReadSecretEndpoint() throws Exception {
        assertThat(PaymentSecretController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/payment-terminal/secrets");
        assertThat(PaymentSecretController.class.getAnnotation(PreAuthorize.class).value())
                .contains("ADMIN","PAYMENT_TERMINAL_SECRETS");
        Method create=PaymentSecretController.class.getDeclaredMethod("create",PaymentSecretController.SecretWriteRequest.class);
        Method rotate=PaymentSecretController.class.getDeclaredMethod("rotate",String.class,PaymentSecretController.SecretWriteRequest.class);
        Method delete=PaymentSecretController.class.getDeclaredMethod("delete",String.class);
        assertThat(create.getAnnotation(PostMapping.class)).isNotNull();
        assertThat(rotate.getAnnotation(PostMapping.class).value()).containsExactly("/{reference}/rotation");
        assertThat(delete.getAnnotation(DeleteMapping.class)).isNotNull();
        assertThat(java.util.Arrays.stream(PaymentSecretController.class.getDeclaredMethods())
                .noneMatch(method->method.getAnnotation(GetMapping.class)!=null)).isTrue();
    }
}

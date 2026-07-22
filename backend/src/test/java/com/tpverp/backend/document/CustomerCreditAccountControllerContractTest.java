package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

class CustomerCreditAccountControllerContractTest {

    @Test
    void exposesOnlyAReadProtectedScopedAccountEndpoint() throws Exception {
        assertThat(CustomerCreditAccountController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/customer-credit-accounts");
        var method = CustomerCreditAccountController.class.getDeclaredMethod(
                "account", UUID.class, org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/{customerId}");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMER_RECEIVABLES_READ")
                .doesNotContain("CUSTOMER_RECEIVABLES_PAY");
        assertThat(method.getParameters()[0].getAnnotation(PathVariable.class)).isNotNull();
    }
}

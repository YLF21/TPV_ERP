package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class PosCashServiceTransactionContractTest {

    @Test
    void authoritativeCatalogReadKeepsLazyProductIdentifiersInsideTransaction() throws Exception {
        var method = PosCashService.class.getDeclaredMethod(
                "authoritativeCommand", PosCashController.SaleRequest.class);

        var transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}

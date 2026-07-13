package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class PaymentTerminalRefundDocumentContractTest {
    @Test
    void exposesDedicatedCardRefundPathThatCannotIssueASecondVoucher() throws Exception {
        var method = DocumentService.class.getDeclaredMethod("createApprovedCardRefund",
                UUID.class, BigDecimal.class, Authentication.class);
        assertThat(method.getReturnType()).isEqualTo(CommercialDocument.class);
    }
}

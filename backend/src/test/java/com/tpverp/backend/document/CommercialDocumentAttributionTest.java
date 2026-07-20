package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommercialDocumentAttributionTest {

    @Test
    void originTerminalCanBeAssignedOnceAndCannotBeReplaced() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 18), UUID.randomUUID(), BigDecimal.ZERO);
        var origin = UUID.randomUUID();

        document.assignOriginTerminal(origin);
        document.assignOriginTerminal(origin);

        assertThat(document.getTerminalOrigenId()).isEqualTo(origin);
        assertThatThrownBy(() -> document.assignOriginTerminal(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inmutable");
    }
}

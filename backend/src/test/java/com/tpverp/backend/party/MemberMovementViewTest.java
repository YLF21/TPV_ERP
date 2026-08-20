package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberMovementViewTest {

    @Test
    void exposesDocumentAndCategoryTraceabilityWithoutChangingTheMovement() {
        var movement = mock(MemberMovement.class);
        var id = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var previousCategoryId = UUID.randomUUID();
        var newCategoryId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-08-19T20:00:00Z");
        when(movement.getId()).thenReturn(id);
        when(movement.getType()).thenReturn(MemberMovementType.CAMBIO_CATEGORIA);
        when(movement.getBalanceAmount()).thenReturn(BigDecimal.ZERO);
        when(movement.getDocumentId()).thenReturn(documentId);
        when(movement.getPreviousCategoryId()).thenReturn(previousCategoryId);
        when(movement.getNewCategoryId()).thenReturn(newCategoryId);
        when(movement.getReason()).thenReturn("promoción automática");
        when(movement.getCreatedAt()).thenReturn(createdAt);

        var view = MemberLoyaltyService.MemberMovementView.from(movement);

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.documentId()).isEqualTo(documentId);
        assertThat(view.previousCategoryId()).isEqualTo(previousCategoryId);
        assertThat(view.newCategoryId()).isEqualTo(newCategoryId);
        assertThat(view.createdAt()).isEqualTo(createdAt);
    }
}

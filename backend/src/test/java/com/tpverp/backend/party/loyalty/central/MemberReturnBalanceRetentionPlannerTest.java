package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.party.MemberBalanceLotRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlementRepository;
import com.tpverp.backend.party.MemberMovementRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberReturnBalanceRetentionPlannerTest {

    @Test
    void sourceWithoutSettlementIsNoRetentionAndDoesNotRequireCentralClaims() {
        var settlements = mock(MemberDocumentLoyaltySettlementRepository.class);
        var movements = mock(MemberMovementRepository.class);
        var lots = mock(MemberBalanceLotRepository.class);
        var planner = new MemberReturnBalanceRetentionPlanner(settlements, movements, lots);
        var source = mock(CommercialDocument.class);
        var sourceId = UUID.randomUUID();
        when(source.getId()).thenReturn(sourceId);
        when(settlements.findById(sourceId)).thenReturn(Optional.empty());

        var plan = planner.plan(source, java.math.BigDecimal.TEN, java.math.BigDecimal.TEN);

        assertThat(plan.sourceDocumentId()).isEqualTo(sourceId);
        assertThat(plan.attributedAmount()).isZero();
        assertThat(plan.claims()).isEmpty();
        assertThat(plan.memberId()).isNull();
    }
}

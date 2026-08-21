package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentMemberBalanceResolverTest {

    @Test
    void resolvesTheAppliedAmountFromTheFinalizedPaymentSession() {
        var storeId = UUID.randomUUID();
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 19), UUID.randomUUID(), BigDecimal.ZERO);
        var paymentSessions = mock(SalePaymentSessionRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var projection = mock(SalePaymentSessionRepository.MemberBalanceReportTotal.class);
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(projection.getTicketId()).thenReturn(document.getId());
        when(projection.getAmount()).thenReturn(new BigDecimal("0.03"));
        when(paymentSessions.findFinalizedMemberBalanceTotalsByTicketIds(
                eq(storeId), anyCollection())).thenReturn(List.of(projection));
        var resolver = new DocumentMemberBalanceResolver(paymentSessions, organization);

        var result = resolver.resolve(List.of(document));

        assertThat(result.amountFor(document)).isEqualByComparingTo("0.03");
    }
}

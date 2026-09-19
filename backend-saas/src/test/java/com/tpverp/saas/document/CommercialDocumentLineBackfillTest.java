package com.tpverp.saas.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommercialDocumentLineBackfillTest {
    @Test
    void processesOnlyTheRequestedBatchAndCountsCompletedDocuments() {
        var lines = mock(CommercialDocumentLineProjectionRepository.class);
        var first = new CommercialDocumentLineProjectionRepository.DocumentKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var second = new CommercialDocumentLineProjectionRepository.DocumentKey(first.companyId(), first.storeId(), UUID.randomUUID());
        when(lines.pending(2)).thenReturn(List.of(first, second));
        when(lines.backfill(first)).thenReturn(true);
        when(lines.backfill(second)).thenReturn(false);
        assertThat(new CommercialDocumentLineBackfill(lines, 2).runBatch()).isEqualTo(1);
        verify(lines).pending(2);
        verify(lines).backfill(first);
        verify(lines).backfill(second);
        assertThatThrownBy(() -> new CommercialDocumentLineBackfill(lines, 1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommercialDocumentLineBackfill(lines, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

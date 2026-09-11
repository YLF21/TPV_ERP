package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentQuery.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentReadServiceTest {
    private final CommercialDocumentReadRepository repository = mock(CommercialDocumentReadRepository.class);
    private final CommercialDocumentReadService service = new CommercialDocumentReadService(repository);
    private final Scope scope = Scope.company(UUID.randomUUID());
    private final Filter filter = new Filter(Set.of(), null, Set.of(Type.TICKET), Set.of(Status.PAGADO), null, null, null, null);

    @Test
    void readsOneExtraRowAndMakesCursorFromLastVisibleRowNotLookahead() {
        Row first = row();
        Row second = row();
        when(repository.page(eq(scope), eq(filter), eq(Order.newestFirst()), isNull(), eq(2))).thenReturn(List.of(first, second));
        Page page = service.page(scope, filter, new PageRequest(Order.newestFirst(), 1, null));
        assertThat(page.items()).containsExactly(first);
        assertThat(page.hasMore()).isTrue();
        var cursor = CommercialDocumentCursor.decode(page.nextCursor(), CommercialDocumentCursor.fingerprint(scope, filter, Order.newestFirst()), Order.newestFirst());
        assertThat(cursor.documentId()).isEqualTo(first.documentId());
        when(repository.page(eq(scope), eq(filter), eq(Order.newestFirst()), eq(cursor), eq(3))).thenReturn(List.of(second));
        Page last = service.page(scope, filter, new PageRequest(Order.newestFirst(), 2, page.nextCursor()));
        assertThat(last.items()).containsExactly(second);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void emptyPageDoesNotInventCursorOrCount() {
        when(repository.page(any(), any(), any(), isNull(), eq(201))).thenReturn(List.of());
        Page page = service.page(scope, filter, new PageRequest(Order.newestFirst(), 200, null));
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void rejectsMismatchedCursorBeforeRepositoryAccess() {
        Order order = Order.newestFirst();
        var cursor = CommercialDocumentCursor.from(row(), CommercialDocumentCursor.fingerprint(Scope.company(UUID.randomUUID()), filter, order), order);
        assertThatThrownBy(() -> service.page(scope, filter, new PageRequest(order, 20, cursor.encode())))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void totalsUseFullFilteredAggregateAndRefuseSilentTruncation() {
        Aggregation grouping = new Aggregation(Period.MONTH, Set.of(Dimension.STORE));
        Total total = new Total(new Group(LocalDate.of(2024, 2, 1), UUID.randomUUID(), null, null, null, null, null, null,
                Type.TICKET, Status.PAGADO, "EUR"), 500L, new BigDecimal("-100.00"), BigDecimal.ZERO, new BigDecimal("-100.00"));
        when(repository.aggregate(scope, filter, grouping, 2001)).thenReturn(List.of(total));
        assertThat(service.documentTotals(scope, filter, grouping).items()).containsExactly(total);
        when(repository.aggregate(scope, filter, grouping, 2001)).thenReturn(Collections.nCopies(2000, total));
        assertThat(service.documentTotals(scope, filter, grouping).items()).hasSize(2000);
        when(repository.aggregate(scope, filter, grouping, 2001)).thenReturn(Collections.nCopies(2001, total));
        assertThatThrownBy(() -> service.documentTotals(scope, filter, grouping))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(422));
        verify(repository, never()).page(any(), any(), any(), any(), anyInt());
    }

    private Row row() {
        return new Row(scope.companyId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, Type.TICKET, Status.PAGADO,
                "001", LocalDate.of(2024, 2, 29), "EUR", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }
}

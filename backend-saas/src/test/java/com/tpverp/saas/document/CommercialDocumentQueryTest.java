package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentQuery.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentQueryTest {
    @Test
    void scopeRequiresExplicitCompanyAndPreservesEmptyRestrictedGrant() {
        UUID company = UUID.randomUUID();
        assertThatThrownBy(() -> Scope.company(null)).isInstanceOf(NullPointerException.class);
        assertThat(Scope.stores(company, Set.of()).companyWide()).isFalse();
        assertThat(Scope.company(company).companyWide()).isTrue();
        assertThatThrownBy(() -> new Scope(company, true, Set.of(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void filtersAreImmutableAndNeverDefaultToHiddenDocumentStatesOrTypes() {
        Set<UUID> stores = new HashSet<>();
        stores.add(UUID.randomUUID());
        Filter filter = filter(stores, Set.of(Type.TICKET), Set.of(Status.PAGADO), null, null, "  A_%  ");
        stores.clear();
        assertThat(filter.storeIds()).hasSize(1);
        assertThat(filter.numberContains()).isEqualTo("A_%");
        assertThat(filter(Set.of(), Set.of(Type.TICKET), Set.of(Status.PAGADO), null, null, " ").numberContains()).isNull();
        assertThatThrownBy(() -> filter(Set.of(), Set.of(), Set.of(Status.PAGADO), null, null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> filter(Set.of(), Set.of(Type.TICKET), Set.of(), null, null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> filter(null, Set.of(Type.TICKET), Set.of(Status.PAGADO), null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void datesAreInclusiveAndCalendarBoundedAndLocalActorsNeedInstallation() {
        LocalDate leapDay = LocalDate.of(2024, 2, 29);
        assertThat(filter(Set.of(), Set.of(Type.TICKET), Set.of(Status.PAGADO), leapDay, leapDay, null).to()).isEqualTo(leapDay);
        assertThatThrownBy(() -> filter(Set.of(), Set.of(Type.TICKET), Set.of(Status.PAGADO), leapDay.plusDays(1), leapDay, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> filter(Set.of(), Set.of(Type.TICKET), Set.of(Status.PAGADO), LocalDate.of(0, 1, 1), null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> filter(Set.of(), Set.of(Type.TICKET), Set.of(Status.PAGADO), null, LocalDate.of(10000, 1, 1), null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new Actor(ActorRole.CREATED_BY, null, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new Actor(null, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 201, Integer.MAX_VALUE})
    void pageSizeIsBounded(int size) {
        assertThatThrownBy(() -> new PageRequest(Order.newestFirst(), size, null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void malformedAndUnboundedInputsFailBeforeQuerying() {
        assertThatThrownBy(() -> new PageRequest(Order.newestFirst(), 20, "x".repeat(2049)))
                .isInstanceOf(ResponseStatusException.class);
        for (String search : new String[]{"x".repeat(121), "a\nb"}) {
            assertThatThrownBy(() -> filter(Set.of(), Set.of(Type.TICKET), Set.of(Status.PAGADO), null, null, search))
                    .isInstanceOf(ResponseStatusException.class);
        }
        Set<UUID> invalidStores = new HashSet<>();
        invalidStores.add(null);
        assertThatThrownBy(() -> Scope.stores(UUID.randomUUID(), invalidStores)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new Order(null, Direction.ASC)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new Aggregation(null, Set.of())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new Aggregation(Period.NONE, null)).isInstanceOf(ResponseStatusException.class);
    }

    private static Filter filter(Set<UUID> stores, Set<Type> types, Set<Status> statuses,
            LocalDate from, LocalDate to, String number) {
        return new Filter(stores, null, types, statuses, from, to, null, number);
    }
}

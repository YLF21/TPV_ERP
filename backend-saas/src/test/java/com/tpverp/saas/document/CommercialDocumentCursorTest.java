package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentQuery.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentCursorTest {
    private final UUID company = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final Scope scope = Scope.company(company);
    private final Filter filter = filter(null, null, null, null, null);

    @ParameterizedTest
    @EnumSource(SortField.class)
    void roundTripsAllWhitelistedSortsAndDirections(SortField field) {
        for (Direction direction : Direction.values()) {
            Order order = new Order(field, direction);
            String fingerprint = CommercialDocumentCursor.fingerprint(scope, filter, order);
            var cursor = CommercialDocumentCursor.from(row(), fingerprint, order);
            var decoded = CommercialDocumentCursor.decode(cursor.encode(), fingerprint, order);
            assertThat(decoded).isEqualTo(cursor);
            assertThat(decoded.jdbcValue(order)).isEqualTo(cursor.jdbcValue(order));
        }
    }

    @Test
    void fingerprintsAreStableForSetOrderButChangeWithEveryAuthorizationAndQueryDimension() {
        Order order = Order.newestFirst();
        String fingerprint = CommercialDocumentCursor.fingerprint(scope, filter, order);
        var cursor = CommercialDocumentCursor.from(row(), fingerprint, order).encode();
        List<Scope> changedScopes = List.of(Scope.company(UUID.randomUUID()), Scope.stores(company, Set.of()), Scope.stores(company, Set.of(store)));
        for (Scope changed : changedScopes) {
            assertThatThrownBy(() -> CommercialDocumentCursor.decode(cursor, CommercialDocumentCursor.fingerprint(changed, filter, order), order))
                    .isInstanceOf(ResponseStatusException.class);
        }
        List<Filter> changedFilters = List.of(
                new Filter(Set.of(store), null, filter.types(), filter.statuses(), null, null, null, null),
                filter(UUID.randomUUID(), null, null, null, null),
                filter(null, LocalDate.of(2024, 1, 1), null, null, null),
                filter(null, null, LocalDate.of(2024, 12, 31), null, null),
                filter(null, null, null, new Actor(ActorRole.CREATED_BY, UUID.randomUUID(), UUID.randomUUID()), null),
                filter(null, null, null, null, "A"),
                new Filter(Set.of(), null, Set.of(Type.TICKET), filter.statuses(), null, null, null, null),
                new Filter(Set.of(), null, filter.types(), Set.of(Status.PAGADO), null, null, null, null));
        for (Filter changed : changedFilters) {
            assertThatThrownBy(() -> CommercialDocumentCursor.decode(cursor, CommercialDocumentCursor.fingerprint(scope, changed, order), order))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThat(CommercialDocumentCursor.fingerprint(scope, filter, new Order(SortField.DATE, Direction.ASC))).isNotEqualTo(fingerprint);
        assertThat(CommercialDocumentCursor.fingerprint(scope, filter, new Order(SortField.TOTAL, Direction.DESC))).isNotEqualTo(fingerprint);
        UUID other = UUID.randomUUID();
        assertThat(CommercialDocumentCursor.fingerprint(Scope.stores(company, new LinkedHashSet<>(List.of(store, other))), filter, order))
                .isEqualTo(CommercialDocumentCursor.fingerprint(Scope.stores(company, new LinkedHashSet<>(List.of(other, store))), filter, order));
    }

    @Test
    void actorRoleAndInstallationRemainPartOfCursorEvenForSameLocalUser() {
        UUID user = UUID.randomUUID();
        UUID installation = UUID.randomUUID();
        Order order = Order.newestFirst();
        String first = CommercialDocumentCursor.fingerprint(scope,
                filter(null, null, null, new Actor(ActorRole.CREATED_BY, installation, user), null), order);
        for (Actor actor : List.of(new Actor(ActorRole.CONFIRMED_BY, installation, user),
                new Actor(ActorRole.CREATED_BY, UUID.randomUUID(), user))) {
            assertThat(CommercialDocumentCursor.fingerprint(scope, filter(null, null, null, actor, null), order)).isNotEqualTo(first);
        }
    }

    @Test
    void rejectsMalformedEnvelopeAndInvalidTypedBoundaries() {
        assertThat(CommercialDocumentCursor.decode(null, "hash", Order.newestFirst())).isNull();
        assertThat(CommercialDocumentCursor.decode(" ", "hash", Order.newestFirst())).isNull();
        for (String raw : List.of("not|base64", "x".repeat(2049), encoded("2|hash|x|x|x"), encoded("1|hash|x|1-1-1-1-1|" + UUID.randomUUID()))) {
            assertThatThrownBy(() -> CommercialDocumentCursor.decode(raw, "hash", Order.newestFirst())).isInstanceOf(ResponseStatusException.class);
        }
        invalidValues(SortField.DATE, "2023-02-29", "0000-01-01", "2024-2-29", "2024-01-01 OR 1=1");
        invalidValues(SortField.NUMBER, "x".repeat(33), " x", "a\nb", "");
        invalidValues(SortField.TYPE, "BORRADOR", "ticket");
        invalidValues(SortField.STATUS, "BORRADOR", "Pagado");
        invalidValues(SortField.TOTAL, "NaN", "1e2", "1,23", "1.231", "100000000000000000.00");
        invalidValues(SortField.BASE, "1.231", "1e2");
        invalidValues(SortField.TAX, "1.231", "NaN");
        invalidValues(SortField.USER, "a\nb", "x".repeat(256));
        invalidValues(SortField.TERMINAL, " x", "x".repeat(256));
        invalidValues(SortField.STORE, "x".repeat(65));
        invalidValues(SortField.CURRENCY, "eur", "US");
    }

    @Test
    void missingAndLongUnicodeLabelsRoundTripWithoutNullBoundaryAmbiguity() {
        for (SortField field : List.of(SortField.USER, SortField.TERMINAL)) {
            var order = new Order(field, Direction.DESC);
            for (String value : List.of("", "店".repeat(255), "a|b~c")) {
                var cursor = new CommercialDocumentCursor("hash", value, store, UUID.randomUUID());
                assertThat(CommercialDocumentCursor.decode(cursor.encode(), "hash", order)).isEqualTo(cursor);
            }
        }
    }

    private void invalidValues(SortField field, String... values) {
        for (String value : values) {
            Order order = new Order(field, Direction.ASC);
            var cursor = new CommercialDocumentCursor("hash", value, store, UUID.randomUUID());
            assertThatThrownBy(() -> CommercialDocumentCursor.decode(cursor.encode(), "hash", order)).isInstanceOf(ResponseStatusException.class);
        }
    }

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Filter filter(UUID customer, LocalDate from, LocalDate to, Actor actor, String search) {
        return new Filter(Set.of(), customer, Set.of(Type.TICKET, Type.FACTURA_VENTA), Set.of(Status.PAGADO, Status.PARCIAL), from, to, actor, search);
    }

    private Row row() {
        return new Row(company, store, UUID.randomUUID(), UUID.randomUUID(), 2L, Type.FACTURA_VENTA, Status.PARCIAL,
                "FV-001|A", LocalDate.of(2024, 2, 29), "EUR", new BigDecimal("-10.00"), new BigDecimal("-0.70"), new BigDecimal("-10.70"),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, false,
                "Usuario", "Caja", "001");
    }
}

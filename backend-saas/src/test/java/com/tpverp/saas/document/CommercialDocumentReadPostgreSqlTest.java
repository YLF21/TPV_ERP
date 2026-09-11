package com.tpverp.saas.document;

import static com.tpverp.saas.SaasTestData.validCif;
import static com.tpverp.saas.document.CommercialDocumentQuery.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.*;
import com.tpverp.saas.sync.SyncEventRequest;
import com.tpverp.saas.sync.SyncEventService;
import com.tpverp.saas.sync.SyncOperation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** Real receiver and SQL reads, using committed synthetic fixtures in an isolated test schema. */
@SpringBootTest(properties = {
        "spring.flyway.default-schema=commercial_document_read_test",
        "spring.datasource.hikari.schema=commercial_document_read_test",
        "spring.jpa.properties.hibernate.default_schema=commercial_document_read_test"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CommercialDocumentReadPostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9300000);
    private static final Set<Type> ALL_TYPES = Set.of(Type.values());
    private static final Set<Status> ALL_STATUSES = Set.of(Status.values());

    @MockitoSpyBean CommercialDocumentReadService reads;
    @Autowired CommercialDocumentQueryService queries;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SyncEventService sync;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void assertIsolatedSchema() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class))
                .isEqualTo("commercial_document_read_test");
    }

    @Test
    void scopesAlwaysIntersectCompanyAndRequestedStoresIncludingEmptyRestrictedAccess() {
        Site first = site();
        Site second = site(first.company(), "002");
        Site foreign = site();
        UUID firstId = publish(first, snapshot("FIRST"));
        UUID secondId = publish(second, snapshot("SECOND"));
        publish(foreign, snapshot("FOREIGN"));
        UUID companyId = first.company().getId();

        assertIds(Scope.company(companyId), filter(), firstId, secondId);
        assertIds(Scope.stores(companyId, Set.of(first.store().getId())), filter(), firstId);
        assertIds(Scope.stores(companyId, Set.of()), filter());
        assertIds(Scope.stores(companyId, Set.of(foreign.store().getId())), filter());
        Filter requested = filter(Set.of(second.store().getId(), foreign.store().getId()), null, null, null, null, null);
        assertIds(Scope.stores(companyId, Set.of(first.store().getId(), second.store().getId())), requested, secondId);
        assertIds(Scope.stores(companyId, Set.of(first.store().getId())), requested);
        assertIds(Scope.company(companyId), filter(Set.of(foreign.store().getId()), null, null, null, null, null));

        assertThat(totals(Scope.stores(companyId, Set.of()), filter(), Period.NONE, Set.of())).isEmpty();
        assertThat(totals(Scope.stores(companyId, Set.of(first.store().getId())), requested, Period.NONE, Set.of()))
                .isEmpty();
        assertThat(totals(Scope.company(companyId), filter(), Period.NONE, Set.of()))
                .singleElement().satisfies(total -> assertThat(total.documentCount()).isEqualTo(2));
    }

    @Test
    void typeStatusAndBusinessDateFiltersComposeWithInclusiveLeapDayBoundaries() {
        Site site = site();
        UUID first = publish(site, snapshot(Type.TICKET, Status.PAGADO, "FIRST", "2024-02-29"));
        UUID last = publish(site, snapshot(Type.FACTURA_VENTA, Status.PARCIAL, "LAST", "2024-03-01"));
        publish(site, snapshot(Type.TICKET, Status.PAGADO, "BEFORE", "2024-02-28"));
        publish(site, snapshot(Type.TICKET, Status.PAGADO, "AFTER", "2024-03-02"));
        publish(site, snapshot(Type.RECTIFICATIVA_VENTA, Status.PAGADO, "OTHER-TYPE", "2024-02-29"));
        publish(site, snapshot(Type.TICKET, Status.ANULADO, "OTHER-STATUS", "2024-03-01"));
        var filter = new Filter(Set.of(), null, Set.of(Type.TICKET, Type.FACTURA_VENTA),
                Set.of(Status.PAGADO, Status.PARCIAL), LocalDate.parse("2024-02-29"),
                LocalDate.parse("2024-03-01"), null, null);

        assertIds(scope(site), filter, first, last);
        assertThat(totals(scope(site), filter, Period.NONE, Set.of()))
                .extracting(Total::documentCount).containsExactlyInAnyOrder(1L, 1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "!%_"})
    void numberSearchTreatsSqlWildcardsAndEscapeCharacterAsLiteral(String literal) {
        Site site = site();
        UUID matching = publish(site, snapshot("PRE" + literal + "POST"));
        publish(site, snapshot("PREXPOST"));
        publish(site, snapshot("PREPOST"));
        publish(site, snapshot("UNRELATED"));
        Filter filter = filter(Set.of(), null, null, null, null, literal);

        assertIds(scope(site), filter, matching);
        assertThat(totals(scope(site), filter, Period.NONE, Set.of()))
                .singleElement().satisfies(total -> assertThat(total.documentCount()).isEqualTo(1));
    }

    @Test
    void actorFiltersRequireRoleAndInstallationEvenWhenLocalUserUuidsRepeat() {
        Site first = site();
        Site second = site(first.company(), "002");
        UUID user = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID created = publish(first, identities(snapshot("CREATED"), null, user, otherUser));
        UUID confirmed = publish(first, identities(snapshot("CONFIRMED"), null, otherUser, user));
        UUID foreignInstallation = publish(second, identities(snapshot("SECOND"), null, user, user));
        publish(first, snapshot("NO-ACTOR"));

        assertIds(scope(first), actorFilter(ActorRole.CREATED_BY, first, user), created);
        assertIds(scope(first), actorFilter(ActorRole.CONFIRMED_BY, first, user), confirmed);
        assertIds(scope(first), actorFilter(ActorRole.CREATED_BY, second, user), foreignInstallation);
        assertIds(scope(first), actorFilter(ActorRole.CONFIRMED_BY, second, user), foreignInstallation);
        assertThat(totals(scope(first), actorFilter(ActorRole.CREATED_BY, first, user), Period.NONE, Set.of()))
                .singleElement().satisfies(total -> assertThat(total.documentCount()).isEqualTo(1));
    }

    @Test
    void customerResolutionUsesExactOwnerAndLateLinksWithoutDroppingUnresolvedDocuments() {
        Site first = site();
        Site second = site(first.company(), "002");
        UUID local = UUID.randomUUID();
        UUID laterLocal = UUID.randomUUID();
        UUID central = customer(first.company());
        link(first.installation().getId(), first.company().getId(), local, central);
        UUID known = publish(first, identities(snapshot("KNOWN"), local, null, null));
        UUID otherInstallation = publish(second, identities(snapshot("OTHER-INSTALLATION"), local, null, null));
        UUID late = publish(first, identities(snapshot("LATE"), laterLocal, null, null));
        UUID anonymous = publish(first, snapshot("ANONYMOUS"));

        assertIds(scope(first), filter(), known, otherInstallation, late, anonymous);
        assertThat(row(first, known).customerId()).isEqualTo(central);
        assertThat(row(first, known).customerCode()).isNotBlank();
        assertThat(row(first, known).customerName()).isEqualTo("Read test customer");
        assertThat(row(first, known).customerTaxId()).isNotBlank();
        assertUnresolved(row(first, otherInstallation));
        assertUnresolved(row(first, late));
        assertIds(scope(first), customerFilter(central), known);

        UUID lateCentral = customer(first.company());
        link(first.installation().getId(), first.company().getId(), laterLocal, lateCentral);
        assertIds(scope(first), customerFilter(lateCentral), late);
        assertThat(row(first, late).sourceRevision()).isEqualTo(1);
        assertThat(row(first, late).customerId()).isEqualTo(lateCentral);
        assertThat(totals(scope(first), customerFilter(lateCentral), Period.NONE, Set.of()))
                .singleElement().satisfies(total -> assertThat(total.documentCount()).isEqualTo(1));
    }

    @Test
    void malformedLegacyLinksCannotExposeAnotherCompanyCustomerOrItsProfile() {
        Site owner = site();
        Site foreign = site();
        UUID localWithWrongLinkCompany = UUID.randomUUID();
        UUID localWithWrongCustomerCompany = UUID.randomUUID();
        UUID localWithWrongInstallation = UUID.randomUUID();
        UUID foreignCustomerOne = customer(foreign.company());
        UUID foreignCustomerTwo = customer(foreign.company());
        UUID sameCompanyCustomer = customer(owner.company());
        // V53 has individual FKs, not composite owner FKs; reads must still check every tenant boundary.
        link(owner.installation().getId(), foreign.company().getId(), localWithWrongLinkCompany, foreignCustomerOne);
        link(owner.installation().getId(), owner.company().getId(), localWithWrongCustomerCompany, foreignCustomerTwo);
        link(foreign.installation().getId(), owner.company().getId(), localWithWrongInstallation, sameCompanyCustomer);
        UUID first = publish(owner, identities(snapshot("WRONG-LINK-COMPANY"), localWithWrongLinkCompany, null, null));
        UUID second = publish(owner, identities(snapshot("WRONG-CUSTOMER-COMPANY"), localWithWrongCustomerCompany, null, null));
        UUID third = publish(owner, identities(snapshot("WRONG-INSTALLATION"), localWithWrongInstallation, null, null));

        assertIds(scope(owner), filter(), first, second, third);
        page(scope(owner), filter(), Order.newestFirst(), 200, null).items().forEach(this::assertUnresolved);
        for (UUID central : List.of(foreignCustomerOne, foreignCustomerTwo, sameCompanyCustomer)) {
            assertIds(scope(owner), customerFilter(central));
            assertThat(totals(scope(owner), customerFilter(central), Period.NONE, Set.of(Dimension.CUSTOMER))).isEmpty();
        }
        assertThat(totals(scope(owner), filter(), Period.NONE, Set.of(Dimension.CUSTOMER)))
                .hasSize(3).allSatisfy(total -> assertThat(total.group().customerId()).isNull());
    }

    @ParameterizedTest
    @MethodSource("orders")
    void keysetTraversesEveryTieExactlyOnceForEachWhitelistedOrder(Order order) {
        Site first = site();
        Site second = site(first.company(), "002");
        var expected = new ArrayList<Expected>();
        Type[] types = {Type.FACTURA_VENTA, Type.TICKET, Type.ALBARAN_VENTA};
        Status[] statuses = {Status.PAGADO, Status.ANULADO, Status.PARCIAL};
        for (int index = 0; index < 9; index++) {
            Site owner = index % 2 == 0 ? first : second;
            String number = "NUMBER-" + index % 3;
            LocalDate date = LocalDate.of(2026, 1, index % 3 + 1);
            BigDecimal total = new BigDecimal(index % 3 - 1).setScale(2);
            Type type = types[index % 3];
            Status state = statuses[index % 3];
            var data = snapshot(type, state, number, date.toString());
            data.put("total", total.toPlainString());
            data.put("subtotal", total.toPlainString());
            data.put("impuestos", total.negate().toPlainString());
            String user = index % 3 == 0 ? null : index % 3 == 1 ? "Ana" : "张";
            String terminal = index % 3 == 2 ? null : "Caja " + index % 2;
            data.put("usuarioNombre", user);
            data.put("terminalOrigenNombre", terminal);
            String currency = index % 2 == 0 ? "EUR" : "USD";
            data.put("moneda", currency);
            UUID id = publish(owner, data);
            expected.add(new Expected(id, owner.store().getId(), date, number, type, state, total,
                    user, terminal, owner.store().getCode(), currency));
        }
        Comparator<Expected> primary = switch (order.field()) {
            case DATE -> Comparator.comparing(Expected::date);
            case NUMBER -> Comparator.comparing(Expected::number);
            case TYPE -> Comparator.comparing(value -> value.type().name());
            case STATUS -> Comparator.comparing(value -> value.status().name());
            case BASE, TOTAL -> Comparator.comparing(Expected::total);
            case TAX -> Comparator.comparing(value -> value.total().negate());
            case USER -> Comparator.comparing(Expected::user, Comparator.nullsLast(order.direction() == Direction.ASC
                    ? Comparator.<String>naturalOrder() : Comparator.<String>reverseOrder()));
            case TERMINAL -> Comparator.comparing(Expected::terminal, Comparator.nullsLast(order.direction() == Direction.ASC
                    ? Comparator.<String>naturalOrder() : Comparator.<String>reverseOrder()));
            case STORE -> Comparator.comparing(Expected::storeCode);
            case CURRENCY -> Comparator.comparing(Expected::currency);
        };
        if (order.direction() == Direction.DESC && order.field() != SortField.USER && order.field() != SortField.TERMINAL) primary = primary.reversed();
        // PostgreSQL UUID ordering is unsigned; its textual order avoids Java UUID's signed-long ordering.
        expected.sort(primary.thenComparing(value -> value.storeId().toString())
                .thenComparing(value -> value.id().toString()));
        var actual = new ArrayList<UUID>();
        var cursors = new HashSet<String>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 10; pageNumber++) {
            Page page = page(scope(first), filter(), order, 2, cursor);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(2);
            actual.addAll(page.items().stream().map(Row::documentId).toList());
            if (!page.hasMore()) {
                assertThat(page.nextCursor()).isNull();
                break;
            }
            assertThat(page.items()).hasSize(2);
            assertThat(page.nextCursor()).isNotBlank();
            assertThat(cursors.add(page.nextCursor())).isTrue();
            cursor = page.nextCursor();
        }
        assertThat(actual).doesNotHaveDuplicates().containsExactlyElementsOf(expected.stream().map(Expected::id).toList());
    }

    static Stream<Arguments> orders() {
        return Stream.of(SortField.values()).flatMap(field -> Stream.of(Direction.values())
                .map(direction -> Arguments.of(new Order(field, direction))));
    }

    @ParameterizedTest
    @EnumSource(Period.class)
    void aggregateUsesWholeInclusiveIntervalAndCalendarPeriodsInsteadOfTheCurrentPage(Period period) {
        Site site = site();
        List<String> dates = List.of("2023-12-31", "2024-01-01", "2024-02-29", "2024-03-31",
                "2024-04-01", "2024-12-31", "2025-01-01");
        for (String date : dates) publish(site, snapshot(Type.TICKET, Status.PAGADO, date, date));
        publish(site, snapshot(Type.TICKET, Status.PAGADO, "OUTSIDE", "2025-01-02"));
        Filter filter = filter(Set.of(), null, LocalDate.parse("2023-12-31"), LocalDate.parse("2025-01-01"), null, null);
        assertThat(page(scope(site), filter, Order.newestFirst(), 1, null).items()).hasSize(1);

        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (String text : dates) {
            LocalDate date = LocalDate.parse(text);
            LocalDate start = switch (period) {
                case NONE -> null;
                case DAY -> date;
                case MONTH -> date.withDayOfMonth(1);
                case QUARTER -> LocalDate.of(date.getYear(), ((date.getMonthValue() - 1) / 3) * 3 + 1, 1);
                case YEAR -> LocalDate.of(date.getYear(), 1, 1);
            };
            counts.merge(start, 1L, Long::sum);
        }
        List<Total> totals = totals(scope(site), filter, period, Set.of());
        assertThat(totals).hasSize(counts.size());
        assertThat(totals.stream().mapToLong(Total::documentCount).sum()).isEqualTo(7);
        totals.forEach(total -> {
            long count = counts.get(total.group().periodStart());
            assertThat(total.documentCount()).isEqualTo(count);
            assertThat(total.total()).isEqualByComparingTo(BigDecimal.valueOf(count));
            // Deliberately non-additive historical fields: this query must not reconstruct tax or total.
            assertThat(total.subtotal()).isEqualByComparingTo(new BigDecimal("10.00").multiply(BigDecimal.valueOf(count)));
            assertThat(total.taxTotal()).isEqualByComparingTo(new BigDecimal("2.34").multiply(BigDecimal.valueOf(count)));
        });
    }

    @Test
    void totalsKeepTypesStatesAndCurrenciesSeparateAndPreserveSignedAndZeroAmounts() {
        Site site = site();
        for (String value : List.of("-5.25", "0.00", "15.25")) {
            var data = snapshot("SIGNED-" + value);
            data.put("subtotal", "1.11");
            data.put("impuestos", "0.03");
            data.put("total", value);
            publish(site, data);
        }
        var credit = snapshot(Type.RECTIFICATIVA_VENTA, Status.PAGADO, "CREDIT", "2026-01-01");
        credit.put("total", "-5.00");
        publish(site, credit);
        publish(site, snapshot(Type.TICKET, Status.ANULADO, "CANCELLED", "2026-01-01"));
        publish(site, snapshot(Type.ALBARAN_VENTA, Status.PENDIENTE, "PENDING", "2026-01-01"));
        var dollars = snapshot("USD");
        dollars.put("moneda", "USD");
        publish(site, dollars);

        List<Total> totals = totals(scope(site), filter(), Period.NONE, Set.of());
        assertThat(totals).hasSize(5);
        Total paidEuroTickets = total(totals, Type.TICKET, Status.PAGADO, "EUR");
        assertThat(paidEuroTickets.documentCount()).isEqualTo(3);
        assertThat(paidEuroTickets.total()).isEqualByComparingTo("10.00");
        assertThat(paidEuroTickets.subtotal()).isEqualByComparingTo("3.33");
        assertThat(paidEuroTickets.taxTotal()).isEqualByComparingTo("0.09");
        assertThat(total(totals, Type.RECTIFICATIVA_VENTA, Status.PAGADO, "EUR").total()).isEqualByComparingTo("-5.00");
        assertThat(total(totals, Type.TICKET, Status.PAGADO, "USD").documentCount()).isEqualTo(1);
        assertThat(total(totals, Type.TICKET, Status.ANULADO, "EUR").documentCount()).isEqualTo(1);
        assertThat(total(totals, Type.ALBARAN_VENTA, Status.PENDIENTE, "EUR").documentCount()).isEqualTo(1);
    }

    @Test
    void optionalDimensionsSeparateStoresUnresolvedCustomersAndInstallationScopedActors() {
        Site first = site();
        Site second = site(first.company(), "002");
        UUID customerLocal = UUID.randomUUID();
        UUID unresolvedLocal = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        UUID confirmer = UUID.randomUUID();
        UUID central = customer(first.company());
        link(first.installation().getId(), first.company().getId(), customerLocal, central);
        publish(first, identities(snapshot("KNOWN"), customerLocal, creator, confirmer));
        publish(first, identities(snapshot("UNRESOLVED-1"), unresolvedLocal, creator, confirmer));
        publish(second, identities(snapshot("UNRESOLVED-2"), unresolvedLocal, creator, confirmer));
        publish(second, snapshot("ANONYMOUS"));

        assertThat(totals(scope(first), filter(), Period.NONE, Set.of())).singleElement()
                .satisfies(total -> {
                    assertThat(total.documentCount()).isEqualTo(4);
                    assertThat(total.group().storeId()).isNull();
                    assertThat(total.group().customerId()).isNull();
                    assertThat(total.group().actorInstallationId()).isNull();
                });
        assertThat(totals(scope(first), filter(), Period.NONE, Set.of(Dimension.STORE)))
                .extracting(Total::documentCount).containsExactlyInAnyOrder(2L, 2L);
        List<Total> customers = totals(scope(first), filter(), Period.NONE, Set.of(Dimension.CUSTOMER));
        assertThat(customers).hasSize(4);
        assertThat(customers).filteredOn(total -> central.equals(total.group().customerId())).singleElement()
                .satisfies(total -> {
                    assertThat(total.group().unresolvedCustomerInstallationId()).isNull();
                    assertThat(total.group().unresolvedCustomerLocalId()).isNull();
                });
        assertThat(customers).filteredOn(total -> unresolvedLocal.equals(total.group().unresolvedCustomerLocalId()))
                .extracting(total -> total.group().unresolvedCustomerInstallationId())
                .containsExactlyInAnyOrder(first.installation().getId(), second.installation().getId());
        for (Dimension dimension : List.of(Dimension.CREATED_BY, Dimension.CONFIRMED_BY)) {
            List<Total> actors = totals(scope(first), filter(), Period.NONE, Set.of(dimension));
            assertThat(actors).hasSize(3);
            assertThat(actors).filteredOn(total -> first.installation().getId().equals(total.group().actorInstallationId()))
                    .singleElement().satisfies(total -> assertThat(total.documentCount()).isEqualTo(2));
            assertThat(actors).filteredOn(total -> total.group().actorInstallationId() == null)
                    .singleElement().satisfies(total -> assertThat(total.documentCount()).isEqualTo(1));
        }
        assertThat(totals(scope(first), filter(), Period.NONE, Set.of(Dimension.values())))
                .hasSize(4).allSatisfy(total -> assertThat(total.documentCount()).isEqualTo(1));
    }

    @Test
    void receiverRevisionsFeedOneLatestRowAndItsMetadataIntoPageAndAggregate() {
        Site site = site();
        UUID id = UUID.randomUUID();
        var first = snapshot(Type.TICKET, Status.PENDIENTE, "ORIGINAL", "2025-12-31");
        first.put("total", "10.00");
        receive(site, id, first);
        var latest = snapshot(Type.TICKET, Status.PAGADO, "LATEST", "2026-03-01");
        UUID creator = UUID.randomUUID();
        UUID confirmer = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        identities(latest, null, creator, confirmer);
        latest.put("sourceRevision", 3L);
        latest.put("total", "25.00");
        latest.put("creadoEn", "2025-12-31T23:59:00Z");
        latest.put("confirmadoEn", "2026-01-01T00:01:00Z");
        latest.put("terminalOrigenId", terminal.toString());
        latest.put("fechaVencimiento", "2026-03-31");
        latest.put("settledByOrigin", true);
        latest.put("relaciones", List.of(Map.of("tipo", "FACTURA_DE", "origenId", UUID.randomUUID().toString())));
        receive(site, id, latest);
        receive(site, id, latest);
        var older = snapshot(Type.TICKET, Status.PARCIAL, "OBSOLETE", "2026-02-01");
        older.put("sourceRevision", 2L);
        older.put("total", "12.00");
        receive(site, id, older);

        assertIds(scope(site), filter(), id);
        Row row = row(site, id);
        assertThat(row.sourceRevision()).isEqualTo(3);
        assertThat(row.status()).isEqualTo(Status.PAGADO);
        assertThat(row.number()).isEqualTo("LATEST");
        assertThat(row.date()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(row.installationId()).isEqualTo(site.installation().getId());
        assertThat(row.createdByLocalId()).isEqualTo(creator);
        assertThat(row.confirmedByLocalId()).isEqualTo(confirmer);
        assertThat(row.originTerminalLocalId()).isEqualTo(terminal);
        assertThat(row.createdAt()).isEqualTo(Instant.parse("2025-12-31T23:59:00Z"));
        assertThat(row.confirmedAt()).isEqualTo(Instant.parse("2026-01-01T00:01:00Z"));
        assertThat(row.dueDate()).isEqualTo(LocalDate.parse("2026-03-31"));
        assertThat(row.settledByOrigin()).isTrue();
        assertThat(row.relationshipsComplete()).isTrue();
        assertIds(scope(site), filter(Set.of(), null, LocalDate.parse("2025-12-31"), LocalDate.parse("2026-02-28"), null, null));
        assertThat(totals(scope(site), filter(), Period.NONE, Set.of())).singleElement().satisfies(total -> {
            assertThat(total.documentCount()).isEqualTo(1);
            assertThat(total.total()).isEqualByComparingTo("25.00");
        });
    }

    @Test
    void highPrecisionAmountsRemainDistinctInNumericSortAndAggregate() {
        Site site = site();
        var low = snapshot("LOW");
        low.put("total", "9007199254740993.01");
        var high = snapshot("HIGH");
        high.put("total", "9007199254740993.02");
        UUID higherId = publish(site, high);
        UUID lowerId = publish(site, low);
        Page first = page(scope(site), filter(), new Order(SortField.TOTAL, Direction.ASC), 1, null);
        assertThat(first.items()).extracting(Row::documentId).containsExactly(lowerId);
        Page second = page(scope(site), filter(), new Order(SortField.TOTAL, Direction.ASC), 1, first.nextCursor());
        assertThat(second.items()).extracting(Row::documentId).containsExactly(higherId);
        assertThat(second.hasMore()).isFalse();
        assertThat(totals(scope(site), filter(), Period.NONE, Set.of())).singleElement()
                .satisfies(total -> assertThat(total.total()).isEqualByComparingTo("18014398509481986.03"));
    }

    private static Filter filter() { return filter(Set.of(), null, null, null, null, null); }

    private static Filter filter(Set<UUID> stores, UUID customer, LocalDate from, LocalDate to, Actor actor, String number) {
        return new Filter(stores, customer, ALL_TYPES, ALL_STATUSES, from, to, actor, number);
    }

    private static Filter customerFilter(UUID centralId) { return filter(Set.of(), centralId, null, null, null, null); }

    private static Filter actorFilter(ActorRole role, Site site, UUID localUserId) {
        return filter(Set.of(), null, null, null, new Actor(role, site.installation().getId(), localUserId), null);
    }

    private static Scope scope(Site site) { return Scope.company(site.company().getId()); }

    @Test
    void apiAuthenticatesActiveInstallationVerifiesBindingAndReturnsCompanyWideExactRows() throws Exception {
        Site first = site();
        Site second = site(first.company(), "002");
        UUID central = customer(first.company());
        UUID localFirst = UUID.randomUUID();
        UUID localSecond = UUID.randomUUID();
        link(first.installation().getId(), first.company().getId(), localFirst, central);
        link(second.installation().getId(), first.company().getId(), localSecond, central);
        publish(first, identities(snapshot("LOCAL"), localFirst, null, null));
        var remoteData = identities(snapshot("REMOTE"), localSecond, null, null);
        remoteData.put("total", "9007199254740993.01");
        remoteData.put("usuarioNombre", "Operador remoto");
        remoteData.put("terminalOrigenNombre", "Caja remota");
        UUID remote = publish(second, remoteData);
        var request = apiContext(first, localFirst, central);
        request.put("reportKey", "tickets"); request.put("size", 200);
        request.put("sortBy", "number"); request.put("sortDirection", "asc");
        String response = mvc.perform(post("/api/v1/commercial-document-queries/page")
                        .header("X-TPV-Installation-Token", first.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var result = mapper.readTree(response);
        assertThat(result.get("items").size()).isEqualTo(2);
        var row = result.get("items").get(1);
        assertThat(row.get("id").asText()).isEqualTo(second.store().getId() + "/" + remote);
        assertThat(row.get("storeCode").asText()).isEqualTo("002");
        assertThat(row.get("total").isTextual()).isTrue();
        assertThat(row.get("total").asText()).isEqualTo("9007199254740993.01");
        assertThat(row.get("userName").asText()).isEqualTo("Operador remoto");
        assertThat(result.get("coverage").asText()).isEqualTo(CommercialDocumentApi.COVERAGE);
        mvc.perform(post("/api/v1/commercial-document-queries/page").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request))).andExpect(status().isUnauthorized());
        request.put("expectedCustomerId", UUID.randomUUID());
        var conflict = mvc.perform(post("/api/v1/commercial-document-queries/page")
                        .header("X-TPV-Installation-Token", first.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(conflict).get("code").asText()).isEqualTo("SAAS_CUSTOMER_BINDING_REQUIRED");
        request.put("expectedCustomerId", central);
        jdbc.update("""
                update saas_installation
                   set active = false, revoked_at = current_timestamp,
                       revoked_by = 'commercial-document-query-test',
                       revocation_reason = 'Synthetic authentication rejection test'
                 where id = ?
                """, first.installation().getId());
        mvc.perform(post("/api/v1/commercial-document-queries/page")
                        .header("X-TPV-Installation-Token", first.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request))).andExpect(status().isUnauthorized());
    }

    @Test
    void apiSelectedExportUsesCompositeKeysOrderAndRejectsForeignCustomerDocuments() {
        Site first = site();
        Site second = site(first.company(), "002");
        UUID central = customer(first.company());
        UUID firstLocal = UUID.randomUUID();
        UUID secondLocal = UUID.randomUUID();
        link(first.installation().getId(), first.company().getId(), firstLocal, central);
        link(second.installation().getId(), first.company().getId(), secondLocal, central);
        UUID shared = UUID.randomUUID();
        receive(first, shared, identities(snapshot("FIRST"), firstLocal, null, null));
        receive(second, shared, identities(snapshot("SECOND"), secondLocal, null, null));
        var keys = List.of(new CommercialDocumentApi.DocumentKey(second.store().getId(), shared),
                new CommercialDocumentApi.DocumentKey(first.store().getId(), shared));
        var request = new CommercialDocumentApi.ExportRequest(first.company().getId(), first.store().getId(),
                firstLocal, central, "tickets", null, "number", "asc", keys);
        assertThat(queries.export(request, first.token()).items()).extracting(CommercialDocumentApi.DocumentRow::number)
                .containsExactly("SECOND", "FIRST");
        UUID unrelated = publish(first, snapshot("UNRELATED"));
        var bad = new CommercialDocumentApi.ExportRequest(first.company().getId(), first.store().getId(),
                firstLocal, central, "tickets", null, "number", "asc",
                List.of(new CommercialDocumentApi.DocumentKey(first.store().getId(), unrelated)));
        assertThatThrownBy(() -> queries.export(bad, first.token()))
                .isInstanceOfSatisfying(CommercialDocumentQueryService.QueryFailure.class,
                        error -> assertThat(error.getCode()).isEqualTo("CUSTOMER_DOCUMENT_SELECTION_UNAVAILABLE"));
    }

    @Test
    void filteredExportKeepsOneSnapshotWhenAnotherTransactionPublishesBetweenPages() {
        Site owner = site();
        UUID central = customer(owner.company());
        UUID local = UUID.randomUUID();
        link(owner.installation().getId(), owner.company().getId(), local, central);
        UUID last = null;
        for (int index = 0; index < 201; index++) {
            last = publish(owner, identities(snapshot("ROW-%03d".formatted(index)), local, null, null));
        }
        UUID lastId = last;
        var updated = identities(snapshot("ROW-200"), local, null, null);
        updated.put("sourceRevision", 2L); updated.put("total", "999.00");
        var pages = new AtomicInteger();
        doAnswer(invocation -> {
            Page result = (Page) invocation.callRealMethod();
            if (pages.incrementAndGet() == 1) {
                CompletableFuture.runAsync(() -> receive(owner, lastId, updated)).get(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(reads).page(any(), any(), any());
        var request = new CommercialDocumentApi.ExportRequest(owner.company().getId(), owner.store().getId(), local,
                central, "tickets", new CommercialDocumentApi.Filters("ROW-", null, null, null), "number", "asc", null);
        var export = queries.export(request, owner.token());
        assertThat(export.items()).hasSize(201).allSatisfy(row -> assertThat(row.total()).isEqualTo("1.00"));
        assertThat(export.totals().getFirst().total()).isEqualTo("201.00");
        assertThat(pages.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select total from saas_commercial_document where company_id = ? and store_id = ? and source_document_id = ?",
                BigDecimal.class, owner.company().getId(), owner.store().getId(), lastId)).isEqualByComparingTo("999.00");
    }

    @Test
    void annualApiUsesAllCompanyStoresQuarterBoundariesAndSignedInvoiceAmountsOnly() throws Exception {
        Site owner = site();
        Site other = site(owner.company(), "002");
        UUID central = customer(owner.company());
        UUID local = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        link(owner.installation().getId(), owner.company().getId(), local, central);
        link(other.installation().getId(), owner.company().getId(), remote, central);
        publish(owner, identities(snapshot(Type.FACTURA_VENTA, Status.PAGADO, "FIRST", "2024-02-29"), local, null, null));
        var negative = identities(snapshot(Type.RECTIFICATIVA_VENTA, Status.PARCIAL, "RETURN", "2024-04-01"), remote, null, null);
        negative.put("total", "-0.25"); publish(other, negative);
        publish(owner, identities(snapshot(Type.TICKET, Status.PAGADO, "TICKET", "2024-02-29"), local, null, null));
        publish(owner, identities(snapshot(Type.FACTURA_VENTA, Status.ANULADO, "CANCELLED", "2024-02-29"), local, null, null));
        publish(owner, identities(snapshot(Type.FACTURA_VENTA, Status.PAGADO, "NEXT-YEAR", "2025-01-01"), local, null, null));
        var request = apiContext(owner, local, central); request.put("year", 2024);
        String response = mvc.perform(post("/api/v1/commercial-document-queries/annual")
                        .header("X-TPV-Installation-Token", owner.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var result = mapper.readTree(response);
        assertThat(result.get("quarters").size()).isEqualTo(4);
        assertThat(result.get("quarters").get(0).get("total").asText()).isEqualTo("1.00");
        assertThat(result.get("quarters").get(1).get("total").asText()).isEqualTo("-0.25");
        assertThat(result.get("totals").get(0).get("total").asText()).isEqualTo("0.75");
        assertThat(result.get("issuer").get("id").asText()).isEqualTo(owner.company().getId().toString());
    }

    private static Map<String, Object> apiContext(Site site, UUID local, UUID central) {
        var context = new LinkedHashMap<String, Object>();
        context.put("companyId", site.company().getId()); context.put("storeId", site.store().getId());
        context.put("localCustomerId", local); context.put("expectedCustomerId", central);
        return context;
    }

    private Page page(Scope scope, Filter filter, Order order, int size, String cursor) {
        return reads.page(scope, filter, new PageRequest(order, size, cursor));
    }

    private List<Total> totals(Scope scope, Filter filter, Period period, Set<Dimension> dimensions) {
        return reads.documentTotals(scope, filter, new Aggregation(period, dimensions)).items();
    }

    private void assertIds(Scope scope, Filter filter, UUID... ids) {
        assertThat(page(scope, filter, Order.newestFirst(), 200, null).items())
                .extracting(Row::documentId).containsExactlyInAnyOrder(ids);
    }

    private Row row(Site site, UUID id) {
        return page(scope(site), filter(), Order.newestFirst(), 200, null).items().stream()
                .filter(row -> row.documentId().equals(id)).findFirst().orElseThrow();
    }

    private void assertUnresolved(Row row) {
        assertThat(row.customerId()).isNull();
        assertThat(row.customerCode()).isNull();
        assertThat(row.customerName()).isNull();
        assertThat(row.customerTaxId()).isNull();
    }

    private static Total total(List<Total> totals, Type type, Status status, String currency) {
        List<Total> matching = totals.stream().filter(total -> total.group().type() == type
                && total.group().status() == status && total.group().currency().equals(currency)).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private UUID publish(Site site, Map<String, Object> data) {
        UUID id = UUID.randomUUID();
        receive(site, id, data);
        return id;
    }

    private void receive(Site site, UUID id, Map<String, Object> data) {
        SyncOperation operation = "ANULADO".equals(data.get("estado")) ? SyncOperation.ANULAR : SyncOperation.ACTUALIZAR;
        sync.receive(new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(),
                null, "DOCUMENTO", id, operation, data), site.token());
    }

    private static Map<String, Object> snapshot(String number) {
        return snapshot(Type.TICKET, Status.PAGADO, number, "2026-01-01");
    }

    private static Map<String, Object> snapshot(Type type, Status status, String number, String date) {
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", 2);
        data.put("sourceRevision", 1L);
        data.put("tipo", type.name());
        data.put("estado", status.name());
        data.put("numero", number);
        data.put("fecha", date);
        data.put("subtotal", "10.00");
        data.put("impuestos", "2.34");
        data.put("total", "1.00");
        data.put("moneda", "EUR");
        return data;
    }

    private static Map<String, Object> identities(Map<String, Object> data, UUID customer, UUID creator, UUID confirmer) {
        if (customer != null) data.put("clienteId", customer.toString());
        if (creator != null) data.put("creadoPor", creator.toString());
        if (confirmer != null) data.put("confirmadoPor", confirmer.toString());
        return data;
    }

    private UUID customer(SaasCompany company) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_erp_customer(id, company_id, code, name, tax_id, document_type, active, created_at)
                values (?, ?, ?, 'Read test customer', ?, 'PASAPORTE', true, now())
                """, id, company.getId(), "C-" + id, "TEST-" + id);
        return id;
    }

    private void link(UUID installationId, UUID companyId, UUID localId, UUID centralId) {
        jdbc.update("""
                insert into saas_customer_identity_link(installation_id, local_customer_id, company_id, customer_id)
                values (?, ?, ?, ?)
                """, installationId, localId, companyId, centralId);
    }

    private Site site() {
        SaasCompany company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), "Read test company",
                validCif("B" + COMPANY_NUMBER.getAndIncrement() + "0"), TaxpayerType.SOCIEDAD,
                TaxRegime.IVA, Instant.now()));
        return site(company, "001");
    }

    private Site site(SaasCompany company, String code) {
        SaasStore store = stores.saveAndFlush(new SaasStore(UUID.randomUUID(), company, code, "Read test store",
                "Atlantic/Canary", Instant.now()));
        SaasLicense license = licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(), company,
                "DOCUMENT-READ-" + UUID.randomUUID(), Instant.now().plusSeconds(86400), 1, 1, Instant.now()));
        String token = tokens.newToken();
        SaasInstallation installation = installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(), company,
                store, license, UUID.randomUUID(), "DOCUMENT-READ", null, tokens.hash(token), Instant.now()));
        return new Site(company, store, installation, token);
    }

    private record Site(SaasCompany company, SaasStore store, SaasInstallation installation, String token) { }
    private record Expected(UUID id, UUID storeId, LocalDate date, String number, Type type, Status status, BigDecimal total,
            String user, String terminal, String storeCode, String currency) { }
}

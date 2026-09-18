package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.persistence.FlywayPostgreSqlConfiguration;
import com.tpverp.backend.security.domain.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayPostgreSqlConfiguration.class)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
class ControlAlertReadPostgreSqlTest {

    private static final String URL = required("TPV_ERP_TEST_DB_URL");
    private static final String USER = required("TPV_ERP_TEST_DB_USER");
    private static final String PASSWORD = required("TPV_ERP_TEST_DB_PASSWORD");
    private static final String SCHEMA = "control_alert_read_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final Instant FROM = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-19T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    static {
        execute("create schema " + SCHEMA);
    }

    @Autowired private ControlAlertRepository alerts;
    @Autowired private ControlEventRepository events;
    @Autowired private ControlRuleRepository rules;
    @Autowired private UserAccountRepository userAccounts;
    @Autowired private ControlAlertReadRepository read;
    @Autowired private ControlAlertHistoryRepository history;
    @Autowired private ControlAlertWorkHistoryRepository workHistory;
    @Autowired private CommercialDocumentRepository documents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL
                + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void sortsByTheRelatedDocumentNumberAndFetchesEventsWithoutPerRowQueries() {
        var fixture = fixture("001");
        alert(fixture, "T-003", FROM.plusSeconds(10));
        alert(fixture, "T-001", FROM.plusSeconds(20));
        alert(fixture, "T-002", FROM.plusSeconds(30));
        flushAndClear();
        var statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var page = list(fixture, 0, 2, "document", "asc");

        assertThat(page.getContent()).extracting(ControlAlertService.AlertSummaryView::documentNumber)
                .containsExactly("T-001", "T-002");
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).allSatisfy(row -> {
            assertThat(row.ruleId()).isEqualTo(fixture.rule().getId());
            assertThat(row.userName()).isEqualTo("Cajero de prueba");
            assertThat(row.data()).containsEntry("reason", "Fixture de lectura");
        });
        assertThat(statistics.getEntityFetchCount()).isZero();

        assertThat(list(fixture, 0, 2, "document", "desc").getContent())
                .extracting(ControlAlertService.AlertSummaryView::documentNumber)
                .containsExactly("T-003", "T-002");
    }

    @Test
    void keepsChronologicalPagesStableAcrossEqualOccurrenceTimesAndLateAlertCreation() {
        var fixture = fixture("001");
        var oldest = alert(fixture, "T-OLD", FROM.plusSeconds(1));
        var firstTie = alert(fixture, "T-TIE-A", FROM.plusSeconds(2));
        var secondTie = alert(fixture, "T-TIE-B", FROM.plusSeconds(2));
        var newest = alert(fixture, "T-NEW", FROM.plusSeconds(3));
        flushAndClear();
        // A delayed detector must not move an old event ahead of more recent operations.
        jdbc.update("update control_alerta set creada_en=?, actualizada_en=? where id=?",
                Timestamp.from(NOW), Timestamp.from(NOW), oldest.getId());
        var ascendingTies = List.of(firstTie.getId(), secondTie.getId()).stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();
        var expectedAscending = List.of(
                oldest.getId(), ascendingTies.get(0), ascendingTies.get(1), newest.getId());
        var expectedDescending = expectedAscending.reversed();

        var ascendingFirst = list(fixture, 0, 2, "occurredAt", "asc");
        var ascendingSecond = list(fixture, 1, 2, "occurredAt", "asc");
        assertThat(ascendingFirst.getContent()).extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactlyElementsOf(expectedAscending.subList(0, 2));
        assertThat(ascendingSecond.getContent()).extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactlyElementsOf(expectedAscending.subList(2, 4));
        assertThat(ascendingFirst.getTotalPages()).isEqualTo(2);
        assertThat(ascendingSecond.hasNext()).isFalse();

        assertThat(list(fixture, 0, 2, "occurredAt", "desc").getContent())
                .extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactlyElementsOf(expectedDescending.subList(0, 2));
        assertThat(list(fixture, 1, 2, "occurredAt", "desc").getContent())
                .extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactlyElementsOf(expectedDescending.subList(2, 4));
        assertThat(list(fixture, 0, 4, null, null).getContent())
                .extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactlyElementsOf(expectedDescending);
    }

    @Test
    void appliesStoreIsolationAndHalfOpenOccurrenceDatesBeforeCountingAndPaging() {
        var fixture = fixture("001");
        var otherStore = fixture("002");
        alert(fixture, "BEFORE", FROM.minusSeconds(1));
        var atStart = alert(fixture, "START", FROM);
        var inside = alert(fixture, "INSIDE", FROM.plusSeconds(1));
        var atLastInstant = alert(fixture, "LAST", TO.minusNanos(1_000));
        alert(fixture, "END-EXCLUDED", TO);
        alert(otherStore, "OTHER-STORE", FROM);
        flushAndClear();

        var first = list(fixture, 0, 2, "occurredAt", "asc");
        var second = list(fixture, 1, 2, "occurredAt", "asc");
        var beyond = list(fixture, 2, 2, "occurredAt", "asc");

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactly(atStart.getId(), inside.getId());
        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.getContent()).extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactly(atLastInstant.getId());
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(3);
    }

    @Test
    void recentAlertsKeepTheFiveLatestStoreEventsAndStableTiesWithoutExtraFetches() {
        var fixture = fixture("001");
        var otherStore = fixture("002");
        var oldest = alert(fixture, "OLD", FROM.plusSeconds(1));
        alert(fixture, "SECOND", FROM.plusSeconds(2));
        var third = alert(fixture, "THIRD", FROM.plusSeconds(3));
        var fourth = alert(fixture, "FOURTH", FROM.plusSeconds(4));
        var fifth = alert(fixture, "FIFTH", FROM.plusSeconds(5));
        var firstTie = alert(fixture, "TIE-A", FROM.plusSeconds(6));
        var secondTie = alert(fixture, "TIE-B", FROM.plusSeconds(6));
        alert(otherStore, "FOREIGN-NEWEST", FROM.plusSeconds(7));
        flushAndClear();
        jdbc.update("update control_alerta set creada_en=?, actualizada_en=? where id=?",
                Timestamp.from(NOW), Timestamp.from(NOW), oldest.getId());
        var ties = List.of(firstTie.getId(), secondTie.getId()).stream()
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        var expected = List.of(ties.get(0), ties.get(1), fifth.getId(), fourth.getId(), third.getId());
        var statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var summary = service(fixture).dashboardSummary();

        assertThat(summary.newCount()).isEqualTo(7);
        assertThat(summary.reviewedCount()).isZero();
        assertThat(summary.recentAlerts()).extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactlyElementsOf(expected);
        assertThat(statistics.getEntityFetchCount()).isZero();
        assertThat(list(fixture, 0, 5, null, null).getContent())
                .extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactlyElementsOf(expected);
    }

    @Test
    void filteredRuleCountsAgreeWithTheQueueForStatusPriorityAssigneeOverdueAndSearch() {
        var fixture = fixture("001");
        var otherStore = fixture("002");
        var emptyRule = rules.save(new ControlRule(
                fixture.storeId(), ControlAlertType.SALE_SCREEN_CLEARED, true,
                Map.of(), fixture.userId(), NOW));
        var matching = workAlert(fixture, "KEEP-001", ControlAlertStatus.NEW,
                ControlAlertPriority.HIGH, fixture.userId(), NOW.minusSeconds(60), FROM);
        workAlert(fixture, "KEEP-REVIEWED", ControlAlertStatus.REVIEWED,
                ControlAlertPriority.HIGH, fixture.userId(), NOW.minusSeconds(60), FROM);
        workAlert(fixture, "KEEP-MEDIUM", ControlAlertStatus.NEW,
                ControlAlertPriority.MEDIUM, fixture.userId(), NOW.minusSeconds(60), FROM);
        workAlert(fixture, "KEEP-UNASSIGNED", ControlAlertStatus.NEW,
                ControlAlertPriority.HIGH, null, NOW.minusSeconds(60), FROM);
        workAlert(fixture, "KEEP-FUTURE", ControlAlertStatus.NEW,
                ControlAlertPriority.HIGH, fixture.userId(), NOW.plusSeconds(60), FROM);
        workAlert(fixture, "OTHER-TEXT", ControlAlertStatus.NEW,
                ControlAlertPriority.HIGH, fixture.userId(), NOW.minusSeconds(60), FROM);
        workAlert(fixture, "KEEP-UPPER-BOUND", ControlAlertStatus.NEW,
                ControlAlertPriority.HIGH, fixture.userId(), NOW.minusSeconds(60), TO);
        workAlert(otherStore, "KEEP-OTHER-STORE", ControlAlertStatus.NEW,
                ControlAlertPriority.HIGH, otherStore.userId(), NOW.minusSeconds(60), FROM);
        flushAndClear();
        var service = service(fixture);

        var queue = service.list(ControlAlertStatus.NEW, null, null,
                ControlAlertPriority.HIGH, fixture.userId(), true, FROM, TO,
                " keep- ", 0, 25, "occurredAt", "desc");
        var groups = service.countsByRule(FROM, TO, ControlAlertStatus.NEW,
                ControlAlertPriority.HIGH, fixture.userId(), true, " keep- ");

        assertThat(queue.getContent()).extracting(ControlAlertService.AlertSummaryView::id)
                .containsExactly(matching.getId());
        assertThat(groups).extracting(ControlAlertService.RuleAlertCountView::ruleId)
                .containsExactlyInAnyOrder(fixture.rule().getId(), emptyRule.getId());
        assertThat(groups.stream().mapToLong(ControlAlertService.RuleAlertCountView::total).sum())
                .isEqualTo(queue.getTotalElements()).isEqualTo(1);
        assertThat(groups).filteredOn(group -> group.ruleId().equals(fixture.rule().getId()))
                .singleElement().satisfies(group -> {
                    assertThat(group.newCount()).isEqualTo(1);
                    assertThat(group.reviewedCount()).isZero();
                    assertThat(group.closedCount()).isZero();
                    assertThat(group.dismissedCount()).isZero();
                });
    }

    @Test
    void defaultsIncludeTheCurrentStoreLocaleAndTimezoneWithoutCreatingAPreference() {
        var fixture = fixture("001");
        var authentication = mock(Authentication.class);
        var service = preferenceService(fixture, authentication, "Atlantic/Canary", "es-ES", NOW);

        assertThat(service.get(authentication)).isEqualTo(preferenceView(
                ControlAlertViewPreferenceService.Settings.defaults(), "Atlantic/Canary", "es-ES"));
        assertThat(jdbc.queryForObject(
                "select count(*) from preferencia_vista_alertas where usuario_id=?",
                Long.class, fixture.userId())).isZero();
    }

    @Test
    void savesAndUpsertsOnlyTheAuthenticatedUsersPreferenceAndReturnsCurrentStoreMetadata() {
        var userA = fixture("001");
        var userB = fixture("002");
        var authenticationA = mock(Authentication.class);
        var authenticationB = mock(Authentication.class);
        var serviceA = preferenceService(userA, authenticationA, "Atlantic/Canary", "es-ES", NOW);
        var serviceB = preferenceService(userB, authenticationB, "Europe/Madrid", "zh-CN", NOW);
        var settingsA = new ControlAlertViewPreferenceService.Settings(
                false, true, false, true, "TODAY", 15, "document", "asc");
        var settingsB = new ControlAlertViewPreferenceService.Settings(
                true, false, true, false, "CURRENT_MONTH", 0, "occurredAt", "desc");

        assertThat(serviceA.save(settingsA, authenticationA))
                .isEqualTo(preferenceView(settingsA, "Atlantic/Canary", "es-ES"));
        assertThat(serviceB.save(settingsB, authenticationB))
                .isEqualTo(preferenceView(settingsB, "Europe/Madrid", "zh-CN"));
        assertThat(serviceA.get(authenticationA))
                .isEqualTo(preferenceView(settingsA, "Atlantic/Canary", "es-ES"));
        assertThat(serviceB.get(authenticationB))
                .isEqualTo(preferenceView(settingsB, "Europe/Madrid", "zh-CN"));

        var updatedA = new ControlAlertViewPreferenceService.Settings(
                true, false, false, false, "LAST_7_DAYS", 60, "status", "desc");
        var later = NOW.plusSeconds(60);
        // Presentation is user-scoped; the timezone and locale describe the current store.
        var serviceAAtAnotherStore = preferenceService(
                userA, authenticationA, "Europe/Madrid", "en-GB", later);
        assertThat(serviceAAtAnotherStore.save(updatedA, authenticationA))
                .isEqualTo(preferenceView(updatedA, "Europe/Madrid", "en-GB"));
        assertThat(serviceA.get(authenticationA))
                .isEqualTo(preferenceView(updatedA, "Atlantic/Canary", "es-ES"));
        assertThat(serviceB.get(authenticationB))
                .isEqualTo(preferenceView(settingsB, "Europe/Madrid", "zh-CN"));
        assertThat(jdbc.queryForObject(
                "select count(*) from preferencia_vista_alertas where usuario_id in (?,?)",
                Long.class, userA.userId(), userB.userId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select created_at from preferencia_vista_alertas where usuario_id=?",
                Timestamp.class, userA.userId())).isEqualTo(Timestamp.from(NOW));
        assertThat(jdbc.queryForObject(
                "select updated_at from preferencia_vista_alertas where usuario_id=?",
                Timestamp.class, userA.userId())).isEqualTo(Timestamp.from(later));
        assertThat(jdbc.queryForObject(
                "select updated_at from preferencia_vista_alertas where usuario_id=?",
                Timestamp.class, userB.userId())).isEqualTo(Timestamp.from(NOW));
    }

    @Test
    void resolvesPageLabelsAndLatestNonblankReviewWithoutLoadingHistoriesPerAlert() {
        var fixture = fixture("001");
        var terminalId = terminal(fixture, "CAJA NORTE");
        for (int index = 0; index < 30; index++) {
            var alert = labeledAlert(fixture, terminalId, null, Map.of("reason", "Prueba"));
            alert.updateWork(ControlAlertPriority.HIGH, fixture.userId(), null, NOW);
            var previous = new ControlAlert.WorkSnapshot(ControlAlertPriority.MEDIUM, null, null);
            workHistory.save(new ControlAlertWorkHistory(alert, previous, "Solo asignación", fixture.userId(), NOW));
            history.save(new ControlAlertHistory(alert, ControlAlertStatus.NEW, ControlAlertStatus.REVIEWED,
                    "Revisado " + index, fixture.userId(), FROM.plusSeconds(10)));
            history.save(new ControlAlertHistory(alert, ControlAlertStatus.REVIEWED, ControlAlertStatus.CLOSED,
                    null, fixture.userId(), FROM.plusSeconds(20)));
        }
        flushAndClear();
        var service = service(fixture);
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var page = service.list(null, null, null, null, null, null, FROM, TO, null, 0, 100, null, null);

        assertThat(page.getContent()).hasSize(30).allSatisfy(row -> {
            assertThat(row.terminalName()).isEqualTo("CAJA NORTE");
            assertThat(row.assigneeName()).isEqualTo("CAJERO DE PRUEBA");
            assertThat(row.reviewComment()).startsWith("Revisado ");
            assertThat(row.terminalId()).isEqualTo(terminalId);
            assertThat(row.assigneeId()).isEqualTo(fixture.userId());
        });
        // Page + labels (and a count if Spring Data needs it), independent of the 30 histories.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        assertThat(statistics.getEntityStatistics(ControlAlertHistory.class.getName()).getLoadCount()).isZero();
        assertThat(statistics.getEntityStatistics(ControlAlertWorkHistory.class.getName()).getLoadCount()).isZero();
        assertThat(service.dashboardSummary().recentAlerts()).hasSize(5)
                .allSatisfy(row -> assertThat(row.terminalName()).isEqualTo("CAJA NORTE"));
    }

    @Test
    void breaksCommentTiesByIdAndIgnoresEmptyCommentsAndWorkNotes() {
        var fixture = fixture("001");
        var alert = alert(fixture, "T-REVIEW", FROM);
        var blank = alert(fixture, "T-BLANK", FROM);
        flushAndClear();
        review(alert.getId(), fixture, "00000000-0000-0000-0000-000000000001", "Primero", FROM);
        review(alert.getId(), fixture, "00000000-0000-0000-0000-000000000002", "Segundo", FROM);
        review(alert.getId(), fixture, "00000000-0000-0000-0000-000000000003", " \t\n ", FROM.plusSeconds(1));
        review(blank.getId(), fixture, "00000000-0000-0000-0000-000000000004", "   ", FROM);

        var result = service(fixture).get(alert.getId());

        assertThat(result.alert().reviewComment()).isEqualTo("Segundo");
        assertThat(result.history()).extracting(ControlAlertService.HistoryView::comment)
                .containsExactly("Primero", "Segundo", " \t\n ");
        assertThat(service(fixture).get(blank.getId()).alert().reviewComment()).isNull();
    }

    @Test
    void returnsNewReviewCommentAndHumanHistoryNamesInTheMutationResponse() {
        var fixture = fixture("001");
        grantUserPermissions(fixture.userId(), "APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE");
        var alert = alert(fixture, "T-REVIEW", FROM);
        flushAndClear();
        var authentication = mock(Authentication.class);
        var service = service(fixture, authentication);

        var assigned = service.updateWork(alert.getId(), new ControlAlertService.WorkUpdateRequest(
                ControlAlertPriority.HIGH, fixture.userId(), null, alert.getVersion(), "Asignado"), authentication);
        assertThat(assigned.alert().assigneeName()).isEqualTo("CAJERO DE PRUEBA");
        assertThat(assigned.alert().reviewComment()).isNull();
        assertThat(assigned.workHistory()).singleElement().satisfies(item -> {
            assertThat(item.changedByName()).isEqualTo("CAJERO DE PRUEBA");
            assertThat(item.previousAssigneeName()).isNull();
            assertThat(item.newAssigneeName()).isEqualTo("CAJERO DE PRUEBA");
        });
        var reviewed = service.transition(alert.getId(), ControlAlertStatus.REVIEWED,
                new ControlAlertService.TransitionRequest(assigned.alert().version(), "  Verificado en caja  "), authentication);

        assertThat(reviewed.alert().reviewComment()).isEqualTo("Verificado en caja");
        assertThat(reviewed.history()).singleElement().satisfies(item -> {
            assertThat(item.changedBy()).isEqualTo(fixture.userId());
            assertThat(item.changedByName()).isEqualTo("CAJERO DE PRUEBA");
        });
        flushAndClear();
        assertThat(service(fixture).get(alert.getId()).alert().reviewComment()).isEqualTo("Verificado en caja");
    }

    @Test
    void enrichesOnlyMatchingHistoricalLinesWithoutChangingOriginalEventOrMoney() {
        var fixture = fixture("001");
        var productId = product(fixture);
        var document = document(fixture, productId);
        var original = Map.<String, Object>of(
                "changedLines", List.of(Map.of("position", 1, "productId", productId.toString(),
                        "originalPrice", new BigDecimal("8.20"), "appliedPrice", new BigDecimal("4.00"))),
                "discountedLines", List.of(Map.of("position", 1, "productId", productId.toString(),
                        "name", "Nombre capturado", "code", "CAPTURED", "discountPercent", 20)),
                "matchingLines", List.of(Map.of("position", 2, "productId", productId.toString()),
                        Map.of("position", 1, "productId", UUID.randomUUID().toString())));
        var alert = labeledAlert(fixture, null, document.getId(), original);
        flushAndClear();
        var stored = jdbc.queryForObject("select datos::text from control_evento where id=?", String.class, alert.getEvent().getId());

        var data = service(fixture).get(alert.getId()).alert().data();

        var changed = (Map<?, ?>) ((List<?>) data.get("changedLines")).getFirst();
        assertThat(changed.get("name")).isEqualTo("Nombre histórico");
        assertThat(changed.get("code")).isEqualTo("HISTORICAL");
        assertThat(new BigDecimal(changed.get("originalPrice").toString())).isEqualByComparingTo("8.20");
        assertThat(new BigDecimal(changed.get("appliedPrice").toString())).isEqualByComparingTo("4");
        var discounted = (Map<?, ?>) ((List<?>) data.get("discountedLines")).getFirst();
        assertThat(discounted.get("name")).isEqualTo("Nombre capturado");
        assertThat(discounted.get("code")).isEqualTo("CAPTURED");
        assertThat((List<?>) data.get("matchingLines")).allSatisfy(value -> {
            assertThat(((Map<?, ?>) value).containsKey("name")).isFalse();
            assertThat(((Map<?, ?>) value).containsKey("code")).isFalse();
        });
        entityManager.flush();
        assertThat(jdbc.queryForObject("select datos::text from control_evento where id=?", String.class,
                alert.getEvent().getId())).isEqualTo(stored);
        assertThat(jdbc.queryForObject("select total from documento where id=?", BigDecimal.class,
                document.getId())).isEqualByComparingTo("4");
    }

    @Test
    void scopesLabelsToTheAlertStoreAndCompanyIncludingRelatedCustomers() {
        var fixture = fixture("001");
        var foreign = fixture("002");
        var foreignTerminal = terminal(foreign, "TERMINAL AJENO");
        var document = document(fixture, product(fixture));
        var foreignDocument = document(foreign, product(foreign));
        var localCustomer = customer(fixture, "Cliente local");
        var foreignCustomer = customer(foreign, "Cliente ajeno");
        jdbc.update("update documento set cliente_id=? where id=?", localCustomer, document.getId());
        jdbc.update("update documento set cliente_id=? where id=?", foreignCustomer, foreignDocument.getId());
        var localAlert = labeledAlert(fixture, null, document.getId(), Map.of());
        var foreignAlert = labeledAlert(foreign, foreignTerminal, foreignDocument.getId(), Map.of());
        var crossReference = labeledAlert(fixture, foreignTerminal, foreignDocument.getId(),
                Map.of("changedLines", List.of(Map.of("position", 1,
                        "productId", foreignDocument.getLineas().getFirst().getProductoId().toString()))));
        crossReference.updateWork(ControlAlertPriority.HIGH, foreign.userId(), null, NOW);
        history.save(new ControlAlertHistory(crossReference, ControlAlertStatus.NEW, ControlAlertStatus.REVIEWED,
                "Referencia cruzada", foreign.userId(), NOW));
        flushAndClear();

        assertThat(service(fixture).relatedDocument(localAlert.getId()).customerName()).isEqualTo("Cliente local");
        assertThat(read.summaryLabels(fixture.storeId(), List.of(foreignAlert.getId()))).isEmpty();
        assertThat(read.evidenceLineLabels(fixture.storeId(), List.of(foreignAlert.getId()))).isEmpty();
        assertThat(read.userLabels(fixture.storeId(), List.of(foreign.userId()))).isEmpty();
        assertThat(read.customerName(fixture.storeId(), foreignDocument.getId())).isNull();
        var crossView = service(fixture).get(crossReference.getId());
        assertThat(crossView.alert().terminalName()).isNull();
        assertThat(crossView.alert().assigneeName()).isNull();
        assertThat(crossView.history()).singleElement().satisfies(item -> assertThat(item.changedByName()).isNull());
        assertThat((List<?>) crossView.alert().data().get("changedLines")).singleElement()
                .satisfies(value -> assertThat(((Map<?, ?>) value).containsKey("name")).isFalse());
        assertThatThrownBy(() -> service(fixture).relatedDocument(crossReference.getId()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> service(fixture).get(foreignAlert.getId()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> service(fixture).relatedDocument(foreignAlert.getId()))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ControlAlertStatus.class, names = {"REVIEWED", "CLOSED", "DISMISSED"})
    void reopensAnExistingAlertAndAppendsHistoryWithoutChangingEvidence(ControlAlertStatus before) {
        var fixture = fixture("001");
        var alert = workAlert(fixture, "T-REOPEN", before, ControlAlertPriority.HIGH,
                fixture.userId(), NOW.plusSeconds(3600), FROM);
        history.save(new ControlAlertHistory(alert, ControlAlertStatus.NEW, before,
                "Revisión original", fixture.userId(), FROM.plusSeconds(1)));
        flushAndClear();
        var authentication = mock(Authentication.class);
        var service = service(fixture, authentication);
        var original = service.get(alert.getId());
        var storedEvidence = jdbc.queryForObject("select datos::text from control_evento where id=?",
                String.class, alert.getEvent().getId());

        var reopened = service.reopen(alert.getId(), new ControlAlertService.TransitionRequest(
                original.alert().version(), "  Reabierta por error de revisión  "), authentication);

        assertThat(reopened.alert().status()).isEqualTo(ControlAlertStatus.NEW);
        assertThat(reopened.alert().version()).isEqualTo(original.alert().version() + 1);
        assertThat(reopened.alert().updatedAt()).isEqualTo(NOW);
        assertThat(reopened.alert().occurredAt()).isEqualTo(original.alert().occurredAt());
        assertThat(reopened.alert().reviewComment()).isEqualTo("Reabierta por error de revisión");
        assertThat(reopened.alert().priority()).isEqualTo(original.alert().priority());
        assertThat(reopened.alert().assigneeId()).isEqualTo(original.alert().assigneeId());
        assertThat(reopened.alert().dueAt()).isEqualTo(original.alert().dueAt());
        assertThat(reopened.history()).hasSize(2);
        assertThat(reopened.history().getFirst()).isEqualTo(original.history().getFirst());
        assertThat(reopened.history().getLast()).satisfies(item -> {
            assertThat(item.previousStatus()).isEqualTo(before);
            assertThat(item.newStatus()).isEqualTo(ControlAlertStatus.NEW);
            assertThat(item.changedAt()).isEqualTo(NOW);
            assertThat(item.changedBy()).isEqualTo(fixture.userId());
            assertThat(item.changedByName()).isEqualTo("CAJERO DE PRUEBA");
            assertThat(item.comment()).isEqualTo("Reabierta por error de revisión");
        });
        assertThat(reopened.workHistory()).isEqualTo(original.workHistory());
        assertThat(service.dashboardSummary().newCount()).isEqualTo(1);
        assertThat(service.dashboardSummary().reviewedCount()).isZero();
        assertThat(service.countsByRule(FROM, TO)).singleElement().satisfies(count -> {
            assertThat(count.total()).isEqualTo(1);
            assertThat(count.newCount()).isEqualTo(1);
        });
        flushAndClear();
        assertThat(service.get(alert.getId()).alert().status()).isEqualTo(ControlAlertStatus.NEW);
        assertThat(jdbc.queryForObject("select creada_en from control_alerta where id=?", Timestamp.class,
                alert.getId())).isEqualTo(Timestamp.from(FROM));
        assertThat(jdbc.queryForObject("select datos::text from control_evento where id=?", String.class,
                alert.getEvent().getId())).isEqualTo(storedEvidence);
    }

    @Test
    void rejectsStaleAndRepeatedReopeningAndForeignStoreWithoutAppendingHistory() {
        var fixture = fixture("001");
        var other = fixture("002");
        var alert = workAlert(fixture, "T-REOPEN", ControlAlertStatus.CLOSED,
                ControlAlertPriority.HIGH, null, null, FROM);
        flushAndClear();
        var authentication = mock(Authentication.class);
        var service = service(fixture, authentication);
        var version = service.get(alert.getId()).alert().version();

        assertThatThrownBy(() -> service.reopen(alert.getId(),
                new ControlAlertService.TransitionRequest(version + 1, "Obsoleta"), authentication))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicto de version");
        assertThatThrownBy(() -> service(other).reopen(alert.getId(),
                new ControlAlertService.TransitionRequest(version, "Otra tienda"), authentication))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(history.findAllByAlertIdOrderByChangedAtAsc(alert.getId())).isEmpty();
        var reopened = service.reopen(alert.getId(), new ControlAlertService.TransitionRequest(version, null), authentication);
        assertThat(reopened.history()).singleElement().satisfies(item -> assertThat(item.comment()).isNull());
        assertThatThrownBy(() -> service.reopen(alert.getId(),
                new ControlAlertService.TransitionRequest(version, "Reintento viejo"), authentication))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicto de version");
        assertThatThrownBy(() -> service.reopen(alert.getId(),
                new ControlAlertService.TransitionRequest(reopened.alert().version(), "Ya nueva"), authentication))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transicion");
        assertThat(history.findAllByAlertIdOrderByChangedAtAsc(alert.getId())).hasSize(1);
        assertThat(service.get(alert.getId()).alert().version()).isEqualTo(reopened.alert().version());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReopeningsWithTheSameVersionCommitOnlyOneHistoryEntry() throws Exception {
        var fixture = fixture("099");
        var alert = workAlert(fixture, "T-CONCURRENT-REOPEN", ControlAlertStatus.CLOSED,
                ControlAlertPriority.HIGH, null, null, FROM);
        var authentication = mock(Authentication.class);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var synchronizedClock = mock(Clock.class);
        when(synchronizedClock.instant()).thenAnswer(ignored -> {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return NOW;
        });
        var service = service(fixture, authentication, synchronizedClock);
        var version = alerts.findById(alert.getId()).orElseThrow().getVersion();
        var transaction = new TransactionTemplate(transactionManager);
        var outcomes = new java.util.ArrayList<Object>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                try {
                    return (Object) transaction.execute(status -> service.reopen(alert.getId(),
                            new ControlAlertService.TransitionRequest(version, "Reapertura " + index), authentication));
                } catch (RuntimeException error) {
                    return (Object) error;
                }
            })).toList();
            for (var future : futures) outcomes.add(future.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertThat(outcomes.stream().filter(ControlAlertService.AlertDetailView.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(IllegalStateException.class::isInstance)).singleElement()
                .satisfies(error -> assertThat(((IllegalStateException) error).getMessage()).contains("Conflicto de version"));
        assertThat(jdbc.queryForObject("select count(*) from control_alerta_historial where alerta_id=?",
                Long.class, alert.getId())).isEqualTo(1);
        assertThat(alerts.findById(alert.getId()).orElseThrow().getVersion()).isEqualTo(version + 1);
        assertThat(alerts.findById(alert.getId()).orElseThrow().getStatus()).isEqualTo(ControlAlertStatus.NEW);
    }

    @Test
    void listsAndAcceptsOnlyActiveAssigneesWhoCanReadAlertsInTheCurrentStoreWithoutPerUserQueries() {
        var fixture = fixture("001");
        var sibling = fixture(fixture.companyId(), "002");
        var foreign = fixture("003");
        var salesOnly = candidate(fixture, "SOLO VENTA", "CASHIER", true, "VENTA");
        var gestionOnly = candidate(fixture, "GESTION SIN ALERTAS", "GESTION", true, "APP_GESTION_ACCESS");
        var alertsWithoutApp = candidate(fixture, "ALERTAS SIN GESTION", "ALERTS", true, "CONTROL_ALERTS_READ");
        // Neither a seller's role/name nor being unprotected prevents access when the permissions allow it.
        var reader = candidate(fixture, "VENDEDOR", "VENDEDOR", true,
                "APP_GESTION_ACCESS", "CONTROL_ALERTS_READ");
        var manager = candidate(fixture, "GESTOR", "SUPERVISOR", true,
                "APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE");
        var admin = candidate(fixture, "ADMIN DE TIENDA", "ADMIN", true);
        var inactive = candidate(fixture, "INACTIVO", "INACTIVE", false,
                "APP_GESTION_ACCESS", "CONTROL_ALERTS_READ");
        var siblingWithoutAccess = candidate(sibling, "OTRA TIENDA SIN ACCESO", "READER", true,
                "APP_GESTION_ACCESS", "CONTROL_ALERTS_READ");
        var siblingWithAccess = candidate(sibling, "OTRA TIENDA CON ACCESO", "MANAGER", true,
                "APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE");
        var foreignWithAccess = candidate(foreign, "OTRA EMPRESA", "ADMIN", true);
        storeAccess(siblingWithAccess, fixture.storeId());
        storeAccess(foreignWithAccess, fixture.storeId());
        flushAndClear();
        var authentication = mock(Authentication.class);
        var service = service(fixture, authentication);
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var options = service.assigneeOptions();

        var eligible = List.of(reader, manager, admin, siblingWithAccess);
        assertThat(options).extracting(ControlAlertService.AssigneeOptionView::id)
                .containsExactlyInAnyOrderElementsOf(eligible);
        assertThat(options).filteredOn(option -> option.id().equals(reader)).singleElement().satisfies(option -> {
            assertThat(option.name()).isEqualTo("VENDEDOR");
            assertThat(option.userName()).isEqualTo("VENDEDOR");
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityFetchCount()).isZero();
        var deniedAlert = alert(fixture, "T-DENIED", FROM);
        flushAndClear();
        var version = service.get(deniedAlert.getId()).alert().version();
        for (var denied : List.of(fixture.userId(), salesOnly, gestionOnly, alertsWithoutApp,
                inactive, siblingWithoutAccess, foreignWithAccess)) {
            assertThatThrownBy(() -> service.updateWork(deniedAlert.getId(), new ControlAlertService.WorkUpdateRequest(
                    ControlAlertPriority.HIGH, denied, null, version, "No debe guardarse"), authentication))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("message.control.alert_assignee_not_eligible");
        }
        assertThat(service.get(deniedAlert.getId())).satisfies(view -> {
            assertThat(view.alert().version()).isEqualTo(version);
            assertThat(view.alert().priority()).isEqualTo(ControlAlertPriority.MEDIUM);
            assertThat(view.alert().assigneeId()).isNull();
            assertThat(view.workHistory()).isEmpty();
        });
        for (var allowed : eligible) {
            var alert = alert(fixture, "T-ALLOWED", FROM);
            flushAndClear();
            var result = service.updateWork(alert.getId(), new ControlAlertService.WorkUpdateRequest(
                    ControlAlertPriority.HIGH, allowed, null, alert.getVersion(), "Asignación válida"), authentication);
            assertThat(result.alert().assigneeId()).isEqualTo(allowed);
            assertThat(result.workHistory()).singleElement()
                    .satisfies(item -> assertThat(item.newAssigneeId()).isEqualTo(allowed));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_GESTION_ACCESS", "CONTROL_ALERTS_READ", "STORE_ACCESS", "ACTIVE"})
    void rejectsAssigneeWhoseAccessWasRevokedAfterLoadingOptions(String revocation) {
        var fixture = fixture("001");
        var sibling = fixture(fixture.companyId(), "002");
        var candidate = candidate(sibling, "RESPONSABLE", "READER", true,
                "APP_GESTION_ACCESS", "CONTROL_ALERTS_READ");
        storeAccess(candidate, fixture.storeId());
        var alert = alert(fixture, "T-REVOCATION", FROM);
        flushAndClear();
        var authentication = mock(Authentication.class);
        var service = service(fixture, authentication);
        assertThat(service.assigneeOptions()).extracting(ControlAlertService.AssigneeOptionView::id).contains(candidate);
        if ("STORE_ACCESS".equals(revocation)) {
            jdbc.update("delete from usuario_tienda where usuario_id=? and tienda_id=?", candidate, fixture.storeId());
        } else if ("ACTIVE".equals(revocation)) {
            jdbc.update("update usuario set activo=false where id=?", candidate);
        } else {
            revokeUserPermission(candidate, revocation);
        }

        assertThatThrownBy(() -> service.updateWork(alert.getId(), new ControlAlertService.WorkUpdateRequest(
                ControlAlertPriority.HIGH, candidate, null, alert.getVersion(), "Lista anterior"), authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.control.alert_assignee_not_eligible");
        assertThat(service.assigneeOptions()).extracting(ControlAlertService.AssigneeOptionView::id).doesNotContain(candidate);
        assertThat(service.get(alert.getId())).satisfies(view -> {
            assertThat(view.alert().assigneeId()).isNull();
            assertThat(view.alert().version()).isEqualTo(alert.getVersion());
            assertThat(view.alert().priority()).isEqualTo(ControlAlertPriority.MEDIUM);
            assertThat(view.workHistory()).isEmpty();
        });
    }

    @Test
    void keepsAnExistingIneligibleAssigneeVisibleAndAllowsUnassignmentWithoutRewritingHistory() {
        var fixture = fixture("001");
        var candidate = candidate(fixture, "ANTIGUO RESPONSABLE", "READER", true,
                "APP_GESTION_ACCESS", "CONTROL_ALERTS_READ");
        var alert = alert(fixture, "T-OLD-ASSIGNEE", FROM);
        flushAndClear();
        var authentication = mock(Authentication.class);
        var service = service(fixture, authentication);
        var assigned = service.updateWork(alert.getId(), new ControlAlertService.WorkUpdateRequest(
                ControlAlertPriority.HIGH, candidate, null, alert.getVersion(), "Asignación original"), authentication);
        revokeUserPermission(candidate, "APP_GESTION_ACCESS");

        var previous = service.get(alert.getId());
        assertThat(previous.alert().assigneeId()).isEqualTo(candidate);
        assertThat(previous.alert().assigneeName()).isEqualTo("ANTIGUO RESPONSABLE");
        assertThat(previous.workHistory()).isEqualTo(assigned.workHistory());
        assertThat(service.assigneeOptions()).extracting(ControlAlertService.AssigneeOptionView::id).doesNotContain(candidate);
        assertThatThrownBy(() -> service.updateWork(alert.getId(), new ControlAlertService.WorkUpdateRequest(
                ControlAlertPriority.CRITICAL, candidate, null, assigned.alert().version(), "No permitido"), authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.control.alert_assignee_not_eligible");
        assertThat(service.get(alert.getId()).workHistory()).isEqualTo(assigned.workHistory());
        var unassigned = service.updateWork(alert.getId(), new ControlAlertService.WorkUpdateRequest(
                assigned.alert().priority(), null, assigned.alert().dueAt(), assigned.alert().version(), "Desasignado"), authentication);

        assertThat(unassigned.alert().assigneeId()).isNull();
        assertThat(unassigned.alert().assigneeName()).isNull();
        assertThat(unassigned.alert().version()).isEqualTo(assigned.alert().version() + 1);
        assertThat(unassigned.workHistory()).hasSize(2).containsAll(assigned.workHistory());
        assertThat(unassigned.workHistory()).filteredOn(item -> item.newAssigneeId() == null).singleElement().satisfies(item -> {
            assertThat(item.previousAssigneeId()).isEqualTo(candidate);
            assertThat(item.previousAssigneeName()).isEqualTo("ANTIGUO RESPONSABLE");
            assertThat(item.comment()).isEqualTo("Desasignado");
        });
    }

    private UUID candidate(Fixture fixture, String name, String roleName, boolean active, String... permissions) {
        var userId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        jdbc.update("insert into rol(id,tienda_id,nombre,protegido) values(?,?,?,?)",
                roleId, fixture.storeId(), roleName, "ADMIN".equals(roleName));
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id,activo) values(?,?,?,?,'fixture-only',?,?)",
                userId, fixture.storeId(), name, name, roleId, active);
        grantUserPermissions(userId, permissions);
        return userId;
    }

    private void grantUserPermissions(UUID userId, String... permissions) {
        for (var permission : permissions) {
            jdbc.update("""
                    insert into permiso(id,codigo,translation_key,grupo) values(?,?,?,'TEST')
                    on conflict(codigo) do nothing
                    """, UUID.randomUUID(), permission, "test." + permission);
            jdbc.update("""
                    insert into rol_permiso(rol_id,permiso_id)
                    select candidate.rol_id, permission.id from usuario candidate cross join permiso permission
                    where candidate.id=? and permission.codigo=? on conflict do nothing
                    """, userId, permission);
        }
    }

    private void revokeUserPermission(UUID userId, String permission) {
        jdbc.update("""
                delete from rol_permiso grant_entry using usuario candidate, permiso permission
                where candidate.id=? and grant_entry.rol_id=candidate.rol_id
                    and grant_entry.permiso_id=permission.id and permission.codigo=?
                """, userId, permission);
    }

    private void storeAccess(UUID userId, UUID storeId) {
        jdbc.update("insert into usuario_tienda(usuario_id,tienda_id) values(?,?) on conflict do nothing", userId, storeId);
    }

    private void review(UUID alertId, Fixture fixture, String id, String comment, Instant at) {
        jdbc.update("""
                insert into control_alerta_historial(id,alerta_id,tienda_id,estado_anterior,estado_nuevo,
                    comentario,cambiado_por,cambiado_en) values(?,?,?,'NEW','REVIEWED',?,?,?)
                """, UUID.fromString(id), alertId, fixture.storeId(), comment, fixture.userId(), Timestamp.from(at));
    }

    private UUID terminal(Fixture fixture, String name) {
        var id = UUID.randomUUID();
        jdbc.update("insert into terminal(id,tienda_id,nombre,tipo,credential_hash) values(?,?,?,'TERMINAL_VENTA','fixture-only')",
                id, fixture.storeId(), name);
        return id;
    }

    private UUID product(Fixture fixture) {
        var familyId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        jdbc.update("insert into familia(id,tienda_id,nombre) values(?,?,'GENERAL')", familyId, fixture.storeId());
        jdbc.update("insert into impuesto_tienda(id,tienda_id,porcentaje) values(?,?,0)", taxId, fixture.storeId());
        jdbc.update("insert into producto(id,tienda_id,familia_id,impuesto_id,nombre) values(?,?,?,?,'Nombre actual diferente')",
                productId, fixture.storeId(), familyId, taxId);
        return productId;
    }

    private CommercialDocument document(Fixture fixture, UUID productId) {
        var warehouse = UUID.randomUUID();
        jdbc.update("insert into almacen(id,tienda_id,nombre) values(?,?,'GENERAL')", warehouse, fixture.storeId());
        var document = new CommercialDocument(fixture.storeId(), warehouse, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 18), fixture.userId(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, productId, 1, 1, "HISTORICAL", "Nombre histórico", "VENTA",
                new BigDecimal("4"), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.confirm("T-READ", fixture.userId(), FROM, false);
        return documents.saveAndFlush(document);
    }

    private UUID customer(Fixture fixture, String name) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into cliente(id,empresa_id,client_id,client_code_store_id,nombre_fiscal,tipo_documento,numero_documento)
                values(?,?,'C-001-000001',?,?,'PASAPORTE','READ-CUSTOMER')
                """, id, fixture.companyId(), fixture.storeId(), name);
        return id;
    }

    private ControlAlert labeledAlert(Fixture fixture, UUID terminalId, UUID documentId, Map<String, Object> data) {
        var event = events.save(new ControlEvent(fixture.storeId(), fixture.rule(), "TEST", UUID.randomUUID(),
                documentId, "T-READ", terminalId, fixture.userId(), "Cajero de prueba", FROM, data));
        return alerts.save(new ControlAlert(event));
    }

    private ControlAlertViewPreferenceService preferenceService(
            Fixture fixture, Authentication authentication, String timezone, String locale, Instant now) {
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getTimezone()).thenReturn(timezone);
        when(store.getLocale()).thenReturn(locale);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication))
                .thenReturn(userAccounts.findById(fixture.userId()).orElseThrow());
        return new ControlAlertViewPreferenceService(
                jdbc, new ObjectMapper(), organization, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static ControlAlertViewPreferenceService.View preferenceView(
            ControlAlertViewPreferenceService.Settings settings, String timezone, String locale) {
        return new ControlAlertViewPreferenceService.View(
                settings.showIndicators(), settings.showDetail(), settings.groupByDay(), settings.compact(),
                settings.defaultPeriod(), settings.refreshSeconds(), settings.sortBy(), settings.sortDirection(),
                timezone, locale);
    }

    private Page<ControlAlertService.AlertSummaryView> list(
            Fixture fixture, int page, int size, String sortBy, String direction) {
        return service(fixture).list(null, null, null, null, null, null,
                FROM, TO, null, page, size, sortBy, direction);
    }

    private ControlAlertService service(Fixture fixture) {
        return service(fixture, mock(Authentication.class));
    }

    private ControlAlertService service(Fixture fixture, Authentication authentication) {
        return service(fixture, authentication, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ControlAlertService service(Fixture fixture, Authentication authentication, Clock serviceClock) {
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        when(store.getId()).thenReturn(fixture.storeId());
        var company = mock(Company.class);
        when(company.getId()).thenReturn(fixture.companyId());
        when(store.getEmpresa()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(userAccounts.findById(fixture.userId()).orElseThrow());
        return new ControlAlertService(alerts, rules, history, workHistory, documents, read,
                organization, serviceClock);
    }

    private ControlAlert alert(Fixture fixture, String documentNumber, Instant occurredAt) {
        var event = events.save(new ControlEvent(
                fixture.storeId(), fixture.rule(), "TEST", UUID.randomUUID(),
                null, documentNumber, null, fixture.userId(), "Cajero de prueba",
                occurredAt, Map.of("reason", "Fixture de lectura")));
        return alerts.save(new ControlAlert(event));
    }

    private ControlAlert workAlert(Fixture fixture, String documentNumber,
            ControlAlertStatus status, ControlAlertPriority priority,
            UUID assigneeId, Instant dueAt, Instant occurredAt) {
        var alert = alert(fixture, documentNumber, occurredAt);
        alert.updateWork(priority, assigneeId, dueAt, NOW);
        if (status != ControlAlertStatus.NEW) alert.transition(status, NOW);
        return alerts.save(alert);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Fixture fixture(String code) {
        return fixture(null, code);
    }

    private Fixture fixture(UUID companyId, String code) {
        if (companyId == null) {
            companyId = UUID.randomUUID();
            jdbc.update("insert into empresa(id,tax_id,razon_social,domicilio_fiscal) values (?,?,?,cast(? as jsonb))",
                    companyId, "B00000" + code, "Empresa de prueba " + code, address());
        }
        var storeId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        jdbc.update("""
                insert into tienda(id,empresa_id,nombre,direccion,address_normalized_hash,
                    timezone,moneda,locale,codigo_tienda)
                values (?,?,?,cast(? as jsonb),?,?,?,?,?)
                """, storeId, companyId, "Tienda " + code, address(), "hash-" + code,
                "Atlantic/Canary", "EUR", "es-ES", code);
        jdbc.update("insert into rol(id,tienda_id,nombre) values (?,?,?)", roleId, storeId, "SELLER");
        jdbc.update("insert into usuario(id,tienda_id,nombre,user_name,password_hash,rol_id) values (?,?,?,?,?,?)",
                userId, storeId, "CAJERO DE PRUEBA", "read-test-" + code, "fixture-only", roleId);
        var rule = rules.save(new ControlRule(storeId, ControlAlertType.TICKET_CANCELLED,
                true, Map.of(), userId, FROM.minusSeconds(60)));
        return new Fixture(storeId, userId, rule, companyId);
    }

    private static String address() {
        return "{\"linea1\":\"x\",\"ciudad\":\"x\",\"codigoPostal\":\"1\",\"provincia\":\"x\",\"pais\":\"ES\"}";
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void execute(String sql) {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(UUID storeId, UUID userId, ControlRule rule, UUID companyId) {}
}

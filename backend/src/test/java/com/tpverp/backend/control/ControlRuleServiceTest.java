package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ControlRuleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    private ControlRuleRepository rules;
    private ControlRuleVersionRepository versions;
    private CurrentOrganization organization;
    private Store store;
    private UserAccount user;
    private ControlRuleService service;

    @BeforeEach
    void setUp() {
        rules = mock(ControlRuleRepository.class);
        versions = mock(ControlRuleVersionRepository.class);
        organization = mock(CurrentOrganization.class);
        store = mock(Store.class);
        user = mock(UserAccount.class);
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(user);
        service = new ControlRuleService(
                rules, versions, organization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void catalogContainsSystemTypesAndMarksConfiguredRules() {
        var configured = new ControlRule(
                store.getId(), ControlAlertType.TICKET_CANCELLED, true, Map.of(), user.getId(), NOW);
        when(rules.findAllByStoreIdOrderByTypeAsc(store.getId())).thenReturn(List.of(configured));

        var catalog = service.catalog();

        assertThat(catalog).hasSize(8);
        assertThat(catalog).filteredOn(item -> item.type() == ControlAlertType.TICKET_CANCELLED)
                .singleElement().satisfies(item -> {
                    assertThat(item.name()).isEqualTo(ControlAlertType.TICKET_CANCELLED.systemName());
                    assertThat(item.configured()).isTrue();
                    assertThat(item.ruleId()).isEqualTo(configured.getId());
                });
        assertThat(catalog).filteredOn(item -> item.type() == ControlAlertType.CONSECUTIVE_LINE_DELETIONS)
                .singleElement().satisfies(item -> {
                    assertThat(item.parameterKind()).isEqualTo(ControlRuleParameterKind.QUANTITY);
                    assertThat(item.defaultConfiguration()).containsEntry("minimumCount", 3);
                    assertThat(item.supported()).isTrue();
                });
        assertThat(catalog).filteredOn(item -> item.type() == ControlAlertType.MANUAL_PRICE_CHANGED)
                .singleElement().extracting(ControlRuleService.RuleCatalogView::supported)
                .isEqualTo(false);
    }

    @Test
    void rejectsDuplicateAndUnsupportedRuleBeforePersisting() {
        when(rules.existsByStoreIdAndType(store.getId(), ControlAlertType.TICKET_CANCELLED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new ControlRuleService.CreateRuleRequest(
                        ControlAlertType.TICKET_CANCELLED, true, Map.of()), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya tiene configurada");
        assertThatThrownBy(() -> service.create(
                new ControlRuleService.CreateRuleRequest(
                        ControlAlertType.MANUAL_PRICE_CHANGED, false, Map.of()), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no esta disponible");

        verify(rules, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createsRuleWithFixedSystemNameAndTypedConfiguration() {
        when(rules.saveAndFlush(org.mockito.ArgumentMatchers.any(ControlRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.create(
                new ControlRuleService.CreateRuleRequest(
                        ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT,
                        true,
                        Map.of("thresholdPercent", 12)),
                authentication());

        assertThat(created.name()).isEqualTo(ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT.systemName());
        assertThat(created.configuration()).containsEntry("thresholdPercent", new java.math.BigDecimal("12"));
        verify(versions).save(org.mockito.ArgumentMatchers.any(ControlRuleVersion.class));
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "token");
    }
}

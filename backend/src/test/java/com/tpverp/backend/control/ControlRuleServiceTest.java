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
import java.util.Optional;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

        assertThat(catalog).hasSize(ControlAlertType.values().length);
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
                .isEqualTo(true);
        assertThat(catalog).filteredOn(
                item -> item.type() == ControlAlertType.MANUAL_PRICE_CHANGE_OVER_PERCENT)
                .singleElement().satisfies(item -> {
                    assertThat(item.parameterKind()).isEqualTo(ControlRuleParameterKind.PERCENTAGE);
                    assertThat(item.defaultConfiguration()).containsEntry("thresholdPercent", 10);
                    assertThat(item.supported()).isTrue();
                });
        assertThat(catalog).filteredOn(item -> item.type() == ControlAlertType.MANUAL_NEGATIVE_QUANTITY)
                .singleElement().satisfies(item -> {
                    assertThat(item.parameterKind()).isEqualTo(ControlRuleParameterKind.NONE);
                    assertThat(item.supported()).isTrue();
                });
        assertThat(catalog).filteredOn(item -> item.type() == ControlAlertType.CASH_DRAWER_OPENED)
                .singleElement().satisfies(item -> {
                    assertThat(item.parameterKind()).isEqualTo(ControlRuleParameterKind.NONE);
                    assertThat(item.supported()).isTrue();
                });
        assertThat(catalog).filteredOn(item -> item.type() == ControlAlertType.PRODUCT_CATALOG_MODIFIED)
                .singleElement().satisfies(item -> {
                    assertThat(item.parameterKind()).isEqualTo(ControlRuleParameterKind.NONE);
                    assertThat(item.supported()).isTrue();
                });
        assertThat(catalog).filteredOn(item -> item.type() == ControlAlertType.CASH_SESSION_DISCREPANCY)
                .singleElement().satisfies(item -> {
                    assertThat(item.parameterKind()).isEqualTo(ControlRuleParameterKind.NONE);
                    assertThat(item.supported()).isTrue();
                });
    }

    @Test
    void rejectsDuplicateRuleBeforePersisting() {
        var existing = rule();
        when(rules.findByStoreIdAndType(store.getId(), ControlAlertType.TICKET_CANCELLED))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(
                new ControlRuleService.CreateRuleRequest(
                        ControlAlertType.TICKET_CANCELLED, true, Map.of()), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya tiene configurada");
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

    @Test
    void retirementDisablesRuleAndAppendsSnapshotWithActorWithoutDeletingAnything() {
        var rule = persistedRule();
        var original = new ControlRuleVersion(rule);
        service.retire(rule.getId(), rule.getVersion(), authentication());

        assertThat(rule.isRetired()).isTrue();
        assertThat(rule.isActive()).isFalse();
        assertThat(rule.getRuleVersion()).isEqualTo(2);
        assertThat(original.isActive()).isTrue();
        assertThat(original.isRetired()).isFalse();
        var snapshot = ArgumentCaptor.forClass(ControlRuleVersion.class);
        verify(versions).save(snapshot.capture());
        assertThat(snapshot.getValue().getRuleId()).isEqualTo(rule.getId());
        assertThat(snapshot.getValue().getRuleVersion()).isEqualTo(2);
        assertThat(snapshot.getValue().isRetired()).isTrue();
        assertThat(snapshot.getValue().isActive()).isFalse();
        assertThat(snapshot.getValue().getChangedBy()).isEqualTo(user.getId());
        assertThat(snapshot.getValue().getChangedAt()).isEqualTo(NOW);
        verify(rules, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(rules, never()).deleteById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retiredRuleDisappearsFromConfigurationButKeepsVersionHistory() {
        var rule = persistedRule();
        rule.retire(user.getId(), NOW);
        when(rules.findAllByStoreIdOrderByTypeAsc(store.getId())).thenReturn(List.of(rule));
        when(versions.findAllByRuleIdOrderByRuleVersionDesc(rule.getId()))
                .thenReturn(List.of(new ControlRuleVersion(rule)));

        assertThat(service.list()).isEmpty();
        assertThat(service.catalog()).filteredOn(item -> item.type() == rule.getType())
                .singleElement().satisfies(item -> {
                    assertThat(item.configured()).isFalse();
                    assertThat(item.ruleId()).isNull();
                });
        assertThat(service.versions(rule.getId())).singleElement()
                .satisfies(item -> assertThat(item.retired()).isTrue());
        assertThatThrownBy(() -> service.get(rule.getId())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.update(rule.getId(), new ControlRuleService.UpdateRuleRequest(
                true, Map.of(), rule.getVersion()), authentication())).isInstanceOf(NoSuchElementException.class);
        verify(rules, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addingRetiredTypeReusesIdentityAndAppendsNextVersion() {
        var rule = persistedRule();
        rule.retire(user.getId(), NOW.minusSeconds(10));
        when(rules.findByStoreIdAndType(store.getId(), rule.getType())).thenReturn(Optional.of(rule));

        var result = service.create(new ControlRuleService.CreateRuleRequest(
                rule.getType(), true, Map.of()), authentication());

        assertThat(result.id()).isEqualTo(rule.getId());
        assertThat(result.ruleVersion()).isEqualTo(3);
        assertThat(result.active()).isTrue();
        assertThat(rule.isRetired()).isFalse();
        var snapshot = ArgumentCaptor.forClass(ControlRuleVersion.class);
        verify(versions).save(snapshot.capture());
        assertThat(snapshot.getValue().getRuleVersion()).isEqualTo(3);
        assertThat(snapshot.getValue().isRetired()).isFalse();
    }

    @Test
    void retirementRejectsMissingOrStaleVersionBeforeMutation() {
        var rule = persistedRule();
        assertThatThrownBy(() -> service.retire(rule.getId(), null, authentication()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.retire(rule.getId(), rule.getVersion() + 1, authentication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicto de version");
        assertThat(rule.isActive()).isTrue();
        assertThat(rule.isRetired()).isFalse();
        verify(rules, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(versions);
    }

    @Test
    void concurrentRetirementFailsWithoutAppendingVersion() {
        var rule = persistedRule();
        when(rules.saveAndFlush(rule)).thenThrow(new ObjectOptimisticLockingFailureException(
                ControlRule.class, rule.getId()));
        assertThatThrownBy(() -> service.retire(rule.getId(), rule.getVersion(), authentication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicto de version");
        org.mockito.Mockito.verifyNoInteractions(versions);
    }

    @Test
    void concurrentRestoreFailsWithoutAppendingVersion() {
        var rule = persistedRule();
        rule.retire(user.getId(), NOW);
        when(rules.findByStoreIdAndType(store.getId(), rule.getType())).thenReturn(Optional.of(rule));
        when(rules.saveAndFlush(rule)).thenThrow(new ObjectOptimisticLockingFailureException(
                ControlRule.class, rule.getId()));
        assertThatThrownBy(() -> service.create(new ControlRuleService.CreateRuleRequest(
                rule.getType(), true, Map.of()), authentication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicto de version");
        org.mockito.Mockito.verifyNoInteractions(versions);
    }

    @Test
    void rejectsRuleOutsideCurrentStoreBeforeRetirementOrHistoryRead() {
        var id = UUID.randomUUID();
        when(rules.findByIdAndStoreId(id, store.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retire(id, 0L, authentication()))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.versions(id)).isInstanceOf(NoSuchElementException.class);
        verify(rules, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(versions);
    }

    private ControlRule rule() {
        return new ControlRule(store.getId(), ControlAlertType.TICKET_CANCELLED, true,
                Map.of(), user.getId(), NOW.minusSeconds(60));
    }

    private ControlRule persistedRule() {
        var rule = rule();
        when(rules.findByIdAndStoreId(rule.getId(), store.getId())).thenReturn(Optional.of(rule));
        when(rules.saveAndFlush(rule)).thenReturn(rule);
        return rule;
    }
}

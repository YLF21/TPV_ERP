package com.tpverp.backend.security.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;

class SaleOperationSecurityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    private SaleOperationSecurityRegistry registry;
    private SaleOperationSecurityConfigurationRepository configurations;
    private CurrentOrganization organization;
    private CurrentTerminal currentTerminal;
    private OperationalPermissionAuthorizationService authorizations;
    private SaleOperationAuthorizationAttemptService authorizationAttempts;
    private AuditService audit;
    private Store store;
    private SaleOperationSecurityService service;

    @BeforeEach
    void setUp() {
        registry = new SaleOperationSecurityRegistry();
        configurations = mock(SaleOperationSecurityConfigurationRepository.class);
        organization = mock(CurrentOrganization.class);
        currentTerminal = mock(CurrentTerminal.class);
        authorizations = mock(OperationalPermissionAuthorizationService.class);
        authorizationAttempts = mock(
                SaleOperationAuthorizationAttemptService.class);
        audit = mock(AuditService.class);
        store = mock(Store.class);
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(organization.currentStore()).thenReturn(store);
        when(configurations.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new SaleOperationSecurityService(
                registry,
                configurations,
                organization,
                currentTerminal,
                authorizations,
                authorizationAttempts,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsCodeDefaultsWithoutCreatingAConfiguration() {
        when(configurations.findById(store.getId())).thenReturn(Optional.empty());

        var current = service.current();

        assertThat(current.storeId()).isEqualTo(store.getId());
        assertThat(current.version()).isZero();
        assertThat(current.operations()).hasSize(SaleOperationCode.values().length).allSatisfy(operation -> {
            assertThat(operation.requirePermission())
                    .isEqualTo(operation.defaultRequirePermission());
            assertThat(operation.requirePassword())
                    .isEqualTo(operation.defaultRequirePassword());
            assertThat(operation.customized()).isFalse();
        });
    }

    @Test
    void savesOnlyDifferencesAndAuditsCompleteBeforeAndAfterSnapshots() {
        when(configurations.findForUpdate(store.getId())).thenReturn(Optional.empty());
        var operations = defaultSettings();
        operations.set(
                SaleOperationCode.OPEN_CASH_DRAWER.ordinal(),
                new SaleOperationSecurityService.OperationSetting(
                        SaleOperationCode.OPEN_CASH_DRAWER,
                        false,
                        true));

        var updated = service.update(0, operations);

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.operations())
                .filteredOn(operation ->
                        operation.code() == SaleOperationCode.OPEN_CASH_DRAWER)
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.requirePermission()).isFalse();
                    assertThat(operation.requirePassword()).isTrue();
                    assertThat(operation.customized()).isTrue();
                });
        var configuration = ArgumentCaptor.forClass(
                SaleOperationSecurityConfiguration.class);
        verify(configurations).saveAndFlush(configuration.capture());
        assertThat(configuration.getValue().getOverrides())
                .singleElement()
                .extracting(SaleOperationSecurityOverride::getOperationCode)
                .isEqualTo(SaleOperationCode.OPEN_CASH_DRAWER);

        @SuppressWarnings("unchecked")
        var details = (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Map.class);
        verify(audit).record(
                org.mockito.ArgumentMatchers.eq(SaleOperationSecurityService.AUDIT_SET),
                org.mockito.ArgumentMatchers.eq(AuditResult.EXITO),
                details.capture());
        assertThat(details.getValue())
                .containsEntry("storeId", store.getId().toString())
                .containsEntry("beforeVersion", 0L)
                .containsEntry("afterVersion", 1L)
                .containsKeys("before", "after");
    }

    @Test
    void resetRemovesAllOverridesAndIncrementsTheLogicalVersion() {
        var configuration = new SaleOperationSecurityConfiguration(store.getId(), NOW);
        configuration.replaceOverrides(
                List.of(new SaleOperationSecurityConfiguration.OverrideValue(
                        SaleOperationCode.OPEN_CASH_DRAWER,
                        false,
                        true)),
                NOW);
        when(configurations.findForUpdate(store.getId()))
                .thenReturn(Optional.of(configuration));

        var reset = service.reset(1);

        assertThat(reset.version()).isEqualTo(2);
        assertThat(reset.operations()).allSatisfy(operation ->
                assertThat(operation.customized()).isFalse());
        assertThat(configuration.getOverrides()).isEmpty();
        verify(audit).record(
                org.mockito.ArgumentMatchers.eq(SaleOperationSecurityService.AUDIT_RESET),
                org.mockito.ArgumentMatchers.eq(AuditResult.EXITO),
                any());
    }

    @Test
    void rejectsStaleOrIncompleteUpdates() {
        var configuration = new SaleOperationSecurityConfiguration(store.getId(), NOW);
        configuration.replaceOverrides(List.of(), NOW);
        when(configurations.findForUpdate(store.getId()))
                .thenReturn(Optional.of(configuration));

        assertThatThrownBy(() -> service.update(0, defaultSettings()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sales_operation_security_version_conflict");
        assertThatThrownBy(() -> service.update(
                1,
                defaultSettings().subList(0, 18)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sales_operation_security_complete_catalog_required");
    }

    @Test
    void translatesConcurrentFirstCreationIntoAStateConflict() {
        when(configurations.findForUpdate(store.getId())).thenReturn(Optional.empty());
        when(configurations.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate store"));

        assertThatThrownBy(() -> service.update(0, defaultSettings()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sales_operation_security_version_conflict");
    }

    @Test
    void resolvesTheEffectivePolicyBeforeAuthorizingAnOperation() {
        var configuration = new SaleOperationSecurityConfiguration(store.getId(), NOW);
        configuration.replaceOverrides(
                List.of(new SaleOperationSecurityConfiguration.OverrideValue(
                        SaleOperationCode.OPEN_CASH_DRAWER,
                        false,
                        true)),
                NOW);
        when(configurations.findById(store.getId()))
                .thenReturn(Optional.of(configuration));
        var authentication = mock(Authentication.class);
        var operator = mock(UserAccount.class);
        var operatorId = UUID.randomUUID();
        when(operator.getId()).thenReturn(operatorId);
        when(operator.getUserName()).thenReturn("CAJERO");
        when(organization.currentUser(authentication)).thenReturn(operator);
        when(currentTerminal.terminalId(authentication)).thenReturn(UUID.randomUUID());
        var reservation = new SaleOperationAuthorizationAttemptService.Reservation(
                UUID.randomUUID(), NOW.plusSeconds(30));
        when(authorizationAttempts.reserve(any(), eq(null)))
                .thenReturn(reservation);
        var expected = mock(
                OperationalPermissionAuthorizationService.Authorization.class);
        when(authorizations.authorize(
                eq(java.util.Set.of(
                        com.tpverp.backend.security.application.CorePermissionBootstrap
                                .ABRIR_CAJON)),
                eq(false),
                eq(true),
                eq(null),
                eq("1234"),
                eq(authentication)))
                .thenReturn(expected);

        var result = service.authorize(
                SaleOperationCode.OPEN_CASH_DRAWER,
                null,
                "1234",
                authentication);

        assertThat(result).isSameAs(expected);
        verify(authorizationAttempts).recordSuccess(any(), eq(reservation));
    }

    @Test
    void recordsCredentialFailureAndReturnsStableDeniedException() {
        when(configurations.findById(store.getId())).thenReturn(Optional.empty());
        var authentication = mock(Authentication.class);
        var operator = mock(UserAccount.class);
        when(operator.getId()).thenReturn(UUID.randomUUID());
        when(operator.getUserName()).thenReturn("CAJERO");
        when(organization.currentUser(authentication)).thenReturn(operator);
        when(currentTerminal.terminalId(authentication)).thenReturn(UUID.randomUUID());
        var reservation = new SaleOperationAuthorizationAttemptService.Reservation(
                UUID.randomUUID(), NOW.plusSeconds(30));
        when(authorizationAttempts.reserve(any(), eq("SUPERVISOR")))
                .thenReturn(reservation);
        when(authorizations.authorize(
                any(), eq(true), eq(true), eq("SUPERVISOR"), eq("bad"),
                eq(authentication)))
                .thenThrow(new IllegalArgumentException("Usuario no valido"));
        when(authorizationAttempts.recordFailure(
                any(), eq(reservation), eq("SUPERVISOR")))
                .thenReturn(new SaleOperationAuthorizationAttemptService.Failure(
                        2, null, 0));

        assertThatThrownBy(() -> service.authorize(
                SaleOperationCode.CANCEL_TICKET,
                "SUPERVISOR",
                "bad",
                authentication))
                .isInstanceOf(SaleOperationAuthorizationDeniedException.class)
                .hasMessage("La autorizacion operativa ha sido rechazada");

        verify(authorizationAttempts).recordFailure(
                any(), eq(reservation), eq("SUPERVISOR"));
        verify(authorizationAttempts, never()).recordSuccess(any(), any());
    }

    @Test
    void rejectsBlockedOperatorBeforeCheckingDelegatedCredentials() {
        when(configurations.findById(store.getId())).thenReturn(Optional.empty());
        var authentication = mock(Authentication.class);
        var operator = mock(UserAccount.class);
        when(operator.getId()).thenReturn(UUID.randomUUID());
        when(operator.getUserName()).thenReturn("CAJERO");
        when(organization.currentUser(authentication)).thenReturn(operator);
        when(currentTerminal.terminalId(authentication)).thenReturn(UUID.randomUUID());
        when(authorizationAttempts.reserve(any(), eq("SUPERVISOR")))
                .thenThrow(new SaleOperationAuthorizationThrottledException(
                        NOW.plusSeconds(15), 15));

        assertThatThrownBy(() -> service.authorize(
                SaleOperationCode.CANCEL_TICKET,
                "SUPERVISOR",
                "secret",
                authentication))
                .isInstanceOf(
                        SaleOperationAuthorizationThrottledException.class)
                .satisfies(exception -> assertThat(
                        ((SaleOperationAuthorizationThrottledException) exception)
                                .retryAfterSeconds()).isEqualTo(15));

        verify(authorizations, never()).authorize(
                any(), any(Boolean.class), any(Boolean.class),
                any(), any(), any());
    }

    private List<SaleOperationSecurityService.OperationSetting> defaultSettings() {
        return registry.definitions().stream()
                .map(definition -> new SaleOperationSecurityService.OperationSetting(
                        definition.code(),
                        definition.defaultRequirePermission(),
                        definition.defaultRequirePassword()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }
}

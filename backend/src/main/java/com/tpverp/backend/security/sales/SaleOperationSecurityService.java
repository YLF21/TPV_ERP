package com.tpverp.backend.security.sales;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.terminal.CurrentTerminal;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleOperationSecurityService {

    public static final String AUDIT_SET = "SALE_OPERATION_SECURITY_SET";
    public static final String AUDIT_RESET = "SALE_OPERATION_SECURITY_RESET";

    private final SaleOperationSecurityRegistry registry;
    private final SaleOperationSecurityConfigurationRepository configurations;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final OperationalPermissionAuthorizationService authorizations;
    private final SaleOperationAuthorizationAttemptService authorizationAttempts;
    private final AuditService audit;
    private final Clock clock;

    public SaleOperationSecurityService(
            SaleOperationSecurityRegistry registry,
            SaleOperationSecurityConfigurationRepository configurations,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            OperationalPermissionAuthorizationService authorizations,
            SaleOperationAuthorizationAttemptService authorizationAttempts,
            AuditService audit,
            Clock clock) {
        this.registry = registry;
        this.configurations = configurations;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.authorizations = authorizations;
        this.authorizationAttempts = authorizationAttempts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConfigurationView current() {
        var storeId = organization.currentStore().getId();
        return view(storeId, configurations.findById(storeId));
    }

    @Transactional
    public ConfigurationView update(
            long expectedVersion,
            List<OperationSetting> operations) {
        requireVersion(expectedVersion);
        var requested = completeSettings(operations);
        var storeId = organization.currentStore().getId();
        var existing = configurations.findForUpdate(storeId);
        requireExpectedVersion(existing, expectedVersion);
        var before = view(storeId, existing);
        var configuration = existing.orElseGet(() ->
                new SaleOperationSecurityConfiguration(storeId, clock.instant()));
        var overrides = requested.values().stream()
                .filter(setting -> differsFromDefault(setting))
                .map(setting -> new SaleOperationSecurityConfiguration.OverrideValue(
                        setting.code(),
                        setting.requirePermission(),
                        setting.requirePassword()))
                .toList();
        configuration.replaceOverrides(overrides, clock.instant());
        var saved = save(configuration);
        var after = view(storeId, Optional.of(saved));
        audit.record(AUDIT_SET, AuditResult.EXITO, auditDetails(before, after));
        return after;
    }

    @Transactional
    public ConfigurationView reset(long expectedVersion) {
        requireVersion(expectedVersion);
        var storeId = organization.currentStore().getId();
        var existing = configurations.findForUpdate(storeId);
        requireExpectedVersion(existing, expectedVersion);
        var before = view(storeId, existing);
        var configuration = existing.orElseGet(() ->
                new SaleOperationSecurityConfiguration(storeId, clock.instant()));
        configuration.replaceOverrides(List.of(), clock.instant());
        var saved = save(configuration);
        var after = view(storeId, Optional.of(saved));
        audit.record(AUDIT_RESET, AuditResult.EXITO, auditDetails(before, after));
        return after;
    }

    @Transactional(readOnly = true)
    public ResolvedOperation resolve(SaleOperationCode code) {
        Objects.requireNonNull(code, "code");
        var storeId = organization.currentStore().getId();
        var configuration = configurations.findById(storeId);
        var definition = registry.require(code);
        var override = configuration
                .flatMap(value -> value.getOverrides().stream()
                        .filter(candidate -> candidate.getOperationCode() == code)
                        .findFirst());
        return resolved(
                storeId,
                configuration.map(SaleOperationSecurityConfiguration::getVersion).orElse(0L),
                definition,
                override);
    }

    @Transactional(readOnly = true)
    public ResolvedOperation resolve(String code) {
        return resolve(registry.require(code).code());
    }

    @Transactional(readOnly = true)
    public Authorization authorize(
            SaleOperationCode code,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var operation = resolve(code);
        if (!requiresCredentialAttempt(operation, authentication)) {
            return authorizations.authorize(
                    Set.copyOf(operation.permissions()),
                    operation.requirePermission(),
                    operation.requirePassword(),
                    authorizerUsername,
                    authorizerPassword,
                    authentication);
        }
        var context = authorizationAttemptContext(operation, authentication);
        var reservation = authorizationAttempts.reserve(
                context, authorizerUsername);
        Authorization authorization;
        try {
            authorization = authorizations.authorize(
                    Set.copyOf(operation.permissions()),
                    operation.requirePermission(),
                    operation.requirePassword(),
                    authorizerUsername,
                    authorizerPassword,
                    authentication);
        } catch (AccessDeniedException | IllegalArgumentException exception) {
            throw authorizationFailure(
                    context, reservation, authorizerUsername, exception);
        }
        authorizationAttempts.recordSuccess(context, reservation);
        return authorization;
    }

    @Transactional(readOnly = true)
    public Authorization authorize(
            String code,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        return authorize(
                registry.require(code).code(),
                authorizerUsername,
                authorizerPassword,
                authentication);
    }

    @Transactional(readOnly = true)
    public Authorization authorizeNamed(
            SaleOperationCode code,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var operation = resolve(code);
        if (!operation.requirePermission()) {
            throw new IllegalStateException(
                    "sales_operation_named_authorizer_requires_permission_policy");
        }
        var context = authorizationAttemptContext(operation, authentication);
        var reservation = authorizationAttempts.reserve(
                context, authorizerUsername);
        Authorization authorization;
        try {
            authorization = authorizations.authorizeNamed(
                    Set.copyOf(operation.permissions()),
                    authorizerUsername,
                    authorizerPassword,
                    authentication);
        } catch (AccessDeniedException | IllegalArgumentException exception) {
            throw authorizationFailure(
                    context, reservation, authorizerUsername, exception);
        }
        authorizationAttempts.recordSuccess(context, reservation);
        return authorization;
    }

    private boolean requiresCredentialAttempt(
            ResolvedOperation operation,
            Authentication authentication) {
        if (operation.requirePassword()) {
            return true;
        }
        return operation.requirePermission()
                && !PermissionChecks.hasRole(authentication, "ADMIN")
                && operation.permissions().stream().noneMatch(permission ->
                        PermissionChecks.hasAuthority(authentication, permission));
    }

    private SaleOperationAuthorizationAttemptService.Context
            authorizationAttemptContext(
                    ResolvedOperation operation,
                    Authentication authentication) {
        var operator = organization.currentUser(authentication);
        return new SaleOperationAuthorizationAttemptService.Context(
                operation.storeId(),
                operator.getId(),
                operator.getUserName(),
                currentTerminal.terminalId(authentication),
                operation.code());
    }

    private RuntimeException authorizationFailure(
            SaleOperationAuthorizationAttemptService.Context context,
            SaleOperationAuthorizationAttemptService.Reservation reservation,
            String authorizerUsername,
            RuntimeException cause) {
        var failure = authorizationAttempts.recordFailure(
                context, reservation, authorizerUsername);
        if (failure.throttled()) {
            return new SaleOperationAuthorizationThrottledException(
                    failure.blockedUntil(),
                    failure.retryAfterSeconds());
        }
        return new SaleOperationAuthorizationDeniedException(cause);
    }

    private ConfigurationView view(
            UUID storeId,
            Optional<SaleOperationSecurityConfiguration> configuration) {
        var overrides = new EnumMap<SaleOperationCode, SaleOperationSecurityOverride>(
                SaleOperationCode.class);
        configuration.ifPresent(value -> value.getOverrides().forEach(
                override -> overrides.put(override.getOperationCode(), override)));
        var operations = registry.definitions().stream()
                .map(definition -> operationView(definition, overrides.get(definition.code())))
                .toList();
        return new ConfigurationView(
                storeId,
                configuration.map(SaleOperationSecurityConfiguration::getVersion).orElse(0L),
                operations);
    }

    private static OperationView operationView(
            SaleOperationDefinition definition,
            SaleOperationSecurityOverride override) {
        var requirePermission = override == null
                ? definition.defaultRequirePermission()
                : override.isRequirePermission();
        var requirePassword = override == null
                ? definition.defaultRequirePassword()
                : override.isRequirePassword();
        return new OperationView(
                definition.code(),
                definition.category(),
                definition.shortcuts(),
                definition.permissions(),
                definition.defaultRequirePermission(),
                definition.defaultRequirePassword(),
                requirePermission,
                requirePassword,
                override != null);
    }

    private static ResolvedOperation resolved(
            UUID storeId,
            long version,
            SaleOperationDefinition definition,
            Optional<SaleOperationSecurityOverride> override) {
        var value = override.orElse(null);
        return new ResolvedOperation(
                storeId,
                version,
                definition.code(),
                definition.category(),
                definition.shortcuts(),
                definition.permissions(),
                value == null
                        ? definition.defaultRequirePermission()
                        : value.isRequirePermission(),
                value == null
                        ? definition.defaultRequirePassword()
                        : value.isRequirePassword(),
                value != null);
    }

    private EnumMap<SaleOperationCode, OperationSetting> completeSettings(
            List<OperationSetting> operations) {
        if (operations == null) {
            throw new IllegalArgumentException(
                    "sales_operation_security_operations_required");
        }
        var settings = new EnumMap<SaleOperationCode, OperationSetting>(
                SaleOperationCode.class);
        for (var operation : operations) {
            if (operation == null || operation.code() == null) {
                throw new IllegalArgumentException(
                        "sales_operation_security_code_required");
            }
            registry.require(operation.code());
            if (settings.put(operation.code(), operation) != null) {
                throw new IllegalArgumentException(
                        "sales_operation_security_duplicate_code");
            }
        }
        if (settings.size() != registry.definitions().size()) {
            throw new IllegalArgumentException(
                    "sales_operation_security_complete_catalog_required");
        }
        return settings;
    }

    private boolean differsFromDefault(OperationSetting setting) {
        var definition = registry.require(setting.code());
        return setting.requirePermission() != definition.defaultRequirePermission()
                || setting.requirePassword() != definition.defaultRequirePassword();
    }

    private static void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "sales_operation_security_version_invalid");
        }
    }

    private static void requireExpectedVersion(
            Optional<SaleOperationSecurityConfiguration> configuration,
            long expectedVersion) {
        var currentVersion = configuration
                .map(SaleOperationSecurityConfiguration::getVersion)
                .orElse(0L);
        if (currentVersion != expectedVersion) {
            throw new IllegalStateException(
                    "sales_operation_security_version_conflict");
        }
    }

    private SaleOperationSecurityConfiguration save(
            SaleOperationSecurityConfiguration configuration) {
        try {
            return configurations.saveAndFlush(configuration);
        } catch (DataIntegrityViolationException
                | ObjectOptimisticLockingFailureException
                | OptimisticLockException exception) {
            throw new IllegalStateException(
                    "sales_operation_security_version_conflict", exception);
        }
    }

    private static Map<String, Object> auditDetails(
            ConfigurationView before,
            ConfigurationView after) {
        var details = new LinkedHashMap<String, Object>();
        details.put("storeId", after.storeId().toString());
        details.put("beforeVersion", before.version());
        details.put("afterVersion", after.version());
        details.put("before", auditOperations(before.operations()));
        details.put("after", auditOperations(after.operations()));
        return details;
    }

    private static List<Map<String, Object>> auditOperations(
            List<OperationView> operations) {
        return operations.stream().map(operation -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("code", operation.code().name());
            value.put("requirePermission", operation.requirePermission());
            value.put("requirePassword", operation.requirePassword());
            value.put("customized", operation.customized());
            return Map.copyOf(value);
        }).toList();
    }

    public record OperationSetting(
            SaleOperationCode code,
            boolean requirePermission,
            boolean requirePassword) {
    }

    public record ConfigurationView(
            UUID storeId,
            long version,
            List<OperationView> operations) {

        public ConfigurationView {
            Objects.requireNonNull(storeId, "storeId");
            operations = List.copyOf(operations);
        }
    }

    public record OperationView(
            SaleOperationCode code,
            SaleOperationCategory category,
            List<String> shortcuts,
            List<String> permissions,
            boolean defaultRequirePermission,
            boolean defaultRequirePassword,
            boolean requirePermission,
            boolean requirePassword,
            boolean customized) {

        public OperationView {
            shortcuts = List.copyOf(shortcuts);
            permissions = List.copyOf(permissions);
        }
    }

    public record ResolvedOperation(
            UUID storeId,
            long version,
            SaleOperationCode code,
            SaleOperationCategory category,
            List<String> shortcuts,
            List<String> permissions,
            boolean requirePermission,
            boolean requirePassword,
            boolean customized) {

        public ResolvedOperation {
            shortcuts = List.copyOf(shortcuts);
            permissions = List.copyOf(permissions);
        }
    }
}

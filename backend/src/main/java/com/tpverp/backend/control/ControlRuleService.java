package com.tpverp.backend.control;

import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlRuleService {

    private final ControlRuleRepository rules;
    private final ControlRuleVersionRepository versions;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ControlRuleService(
            ControlRuleRepository rules,
            ControlRuleVersionRepository versions,
            CurrentOrganization organization,
            Clock clock) {
        this.rules = rules;
        this.versions = versions;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByStoreIdOrderByTypeAsc(organization.currentStore().getId())
                .stream().map(ControlRuleService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<RuleCatalogView> catalog() {
        var storeId = organization.currentStore().getId();
        var configured = rules.findAllByStoreIdOrderByTypeAsc(storeId);
        var configuredTypes = configured.stream().map(ControlRule::getType)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ControlAlertType.class)));
        var ruleIds = configured.stream().collect(java.util.stream.Collectors.toMap(
                ControlRule::getType, ControlRule::getId));
        return java.util.Arrays.stream(ControlAlertType.values())
                .map(type -> new RuleCatalogView(
                        type, type.systemName(), type.parameterKind(), type.defaultConfiguration(),
                        type.supported(), configuredTypes.contains(type), ruleIds.get(type)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RuleView get(UUID id) {
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public List<RuleVersionView> versions(UUID id) {
        var rule = find(id);
        return versions.findAllByRuleIdOrderByRuleVersionDesc(rule.getId()).stream()
                .map(version -> new RuleVersionView(
                        version.getRuleVersion(), version.getType(), version.getName(),
                        version.isActive(), version.getConfiguration(),
                        version.getChangedBy(), version.getChangedAt()))
                .toList();
    }

    @Transactional
    public RuleView create(CreateRuleRequest request, Authentication authentication) {
        requireSupported(request.type());
        var storeId = organization.currentStore().getId();
        if (rules.existsByStoreIdAndType(storeId, request.type())) {
            throw duplicateRule(request.type());
        }
        var user = organization.currentUser(authentication);
        var now = clock.instant();
        var rule = new ControlRule(
                storeId, request.type(),
                request.active(), request.configuration(), user.getId(), now);
        final ControlRule saved;
        try {
            saved = rules.saveAndFlush(rule);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateRule(request.type());
        }
        versions.save(new ControlRuleVersion(saved));
        return view(saved);
    }

    @Transactional
    public RuleView update(UUID id, UpdateRuleRequest request, Authentication authentication) {
        var rule = find(id);
        requireVersion(rule, request.version());
        if (request.active()) requireSupported(rule.getType());
        var user = organization.currentUser(authentication);
        rule.update(request.active(), request.configuration(), user.getId(), clock.instant());
        try {
            var saved = rules.saveAndFlush(rule);
            versions.save(new ControlRuleVersion(saved));
            return view(saved);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw staleVersion(rule.getId(), request.version(), null);
        }
    }

    private ControlRule find(UUID id) {
        return rules.findByIdAndStoreId(id, organization.currentStore().getId())
                .orElseThrow(() -> new NoSuchElementException("Regla de control no encontrada"));
    }

    private static void requireVersion(ControlRule rule, Long expected) {
        if (expected == null) throw new IllegalArgumentException("version es obligatoria");
        if (rule.getVersion() != expected) throw staleVersion(rule.getId(), expected, rule.getVersion());
    }

    private static IllegalStateException staleVersion(UUID id, long expected, Long actual) {
        var detail = actual == null ? "ya fue modificada" : "tiene version " + actual;
        return new IllegalStateException(
                "Conflicto de version en la regla " + id + ": se esperaba " + expected + " y " + detail);
    }

    private static void requireSupported(ControlAlertType type) {
        if (!type.supported()) {
            throw new IllegalStateException(
                    "La regla " + type + " no esta disponible porque no existe una operacion detectable");
        }
    }

    private static IllegalStateException duplicateRule(ControlAlertType type) {
        return new IllegalStateException("La tienda ya tiene configurada la regla " + type);
    }

    private static RuleView view(ControlRule rule) {
        return new RuleView(
                rule.getId(), rule.getType(), rule.getName(), rule.isActive(),
                rule.getConfiguration(), rule.getRuleVersion(), rule.getCreatedBy(),
                rule.getUpdatedBy(), rule.getCreatedAt(), rule.getUpdatedAt(), rule.getVersion());
    }

    public record CreateRuleRequest(
            @NotNull ControlAlertType type,
            boolean active,
            @NotNull Map<String, Object> configuration) {
    }

    public record UpdateRuleRequest(
            boolean active,
            @NotNull Map<String, Object> configuration,
            @NotNull Long version) {
    }

    public record RuleView(
            UUID id,
            ControlAlertType type,
            String name,
            boolean active,
            Map<String, Object> configuration,
            int ruleVersion,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record RuleVersionView(
            int ruleVersion,
            ControlAlertType type,
            String name,
            boolean active,
            Map<String, Object> configuration,
            UUID changedBy,
            Instant changedAt) {
    }

    public record RuleCatalogView(
            ControlAlertType type,
            String name,
            ControlRuleParameterKind parameterKind,
            Map<String, Object> defaultConfiguration,
            boolean supported,
            boolean configured,
            UUID ruleId) {
    }
}

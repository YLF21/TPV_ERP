package com.tpverp.backend.ui;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.CONTROL_ALERTS_MANAGE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CONTROL_ALERTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardPreferenceService {

    private static final List<WidgetDefinition> CATALOG = List.of(
            new WidgetDefinition("sales.today", Set.of(GESTION_VENTAS), 4, 1),
            new WidgetDefinition("sales.top-products", Set.of(GESTION_VENTAS), 8, 2),
            new WidgetDefinition("promotions.active", Set.of(GESTION_PRODUCTO), 4, 2),
            new WidgetDefinition(
                    "control.alerts",
                    Set.of(CONTROL_ALERTS_READ, CONTROL_ALERTS_MANAGE),
                    4,
                    2));
    private static final Set<String> KNOWN_KEYS = CATALOG.stream()
            .map(WidgetDefinition::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final DashboardPreferenceRepository preferences;
    private final CurrentOrganization organization;
    private final Clock clock;

    public DashboardPreferenceService(
            DashboardPreferenceRepository preferences,
            CurrentOrganization organization,
            Clock clock) {
        this.preferences = preferences;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferenceView get(Authentication authentication) {
        var user = organization.currentUser(authentication);
        var widgets = preferences.findByUser(user)
                .map(DashboardPreference::getWidgets)
                .orElseGet(() -> defaults(authentication));
        return view(widgets, authentication);
    }

    @Transactional
    public PreferenceView save(SavePreferenceRequest request, Authentication authentication) {
        Objects.requireNonNull(request, "request");
        var requested = DashboardPreference.validateWidgets(request.widgets());
        validateKnownAndAccessible(requested, authentication);
        var user = organization.currentUser(authentication);
        var existing = preferences.findByUser(user);
        var merged = new ArrayList<>(requested);
        existing.stream()
                .flatMap(preference -> preference.getWidgets().stream())
                .filter(widget -> KNOWN_KEYS.contains(widget.key()))
                .filter(widget -> !canUse(widget.key(), authentication))
                .forEach(merged::add);
        var normalized = DashboardPreference.validateWidgets(merged);
        var preference = existing.orElseGet(() -> new DashboardPreference(
                user, normalized, clock.instant()));
        preference.update(normalized, clock.instant());
        return view(preferences.save(preference).getWidgets(), authentication);
    }

    private PreferenceView view(
            List<DashboardWidgetLayout> widgets,
            Authentication authentication) {
        var visible = widgets.stream()
                .filter(widget -> KNOWN_KEYS.contains(widget.key()))
                .filter(widget -> canUse(widget.key(), authentication))
                .toList();
        var available = CATALOG.stream()
                .filter(definition -> canUse(definition.key(), authentication))
                .map(WidgetDefinition::key)
                .toList();
        return new PreferenceView(visible, available);
    }

    private List<DashboardWidgetLayout> defaults(Authentication authentication) {
        return CATALOG.stream()
                .filter(definition -> canUse(definition.key(), authentication))
                .map(definition -> new DashboardWidgetLayout(
                        definition.key(), definition.defaultWidth(), definition.defaultHeight()))
                .toList();
    }

    private void validateKnownAndAccessible(
            List<DashboardWidgetLayout> widgets,
            Authentication authentication) {
        var rejected = new LinkedHashSet<String>();
        for (var widget : widgets) {
            if (!KNOWN_KEYS.contains(widget.key()) || !canUse(widget.key(), authentication)) {
                rejected.add(widget.key());
            }
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException("widgets no permitidos: " + String.join(", ", rejected));
        }
    }

    private boolean canUse(String key, Authentication authentication) {
        var definition = CATALOG.stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElse(null);
        return definition != null
                && (PermissionChecks.hasRole(authentication, "ADMIN")
                || definition.permissions().stream()
                .anyMatch(permission -> PermissionChecks.hasAuthority(authentication, permission)));
    }

    public record PreferenceView(
            List<DashboardWidgetLayout> widgets,
            List<String> availableWidgets) {

        public PreferenceView {
            widgets = List.copyOf(widgets);
            availableWidgets = List.copyOf(availableWidgets);
        }
    }

    public record SavePreferenceRequest(
            @NotNull
            @Size(max = DashboardPreference.MAX_WIDGETS)
            List<@NotNull @Valid DashboardWidgetLayout> widgets) {
    }

    private record WidgetDefinition(
            String key,
            Set<String> permissions,
            int defaultWidth,
            int defaultHeight) {
    }
}

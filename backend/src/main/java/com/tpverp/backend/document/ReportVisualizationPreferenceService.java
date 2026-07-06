package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Clock;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportVisualizationPreferenceService {

    private final ReportVisualizationPreferenceRepository preferences;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ReportVisualizationPreferenceService(
            ReportVisualizationPreferenceRepository preferences,
            CurrentOrganization organization,
            Clock clock) {
        this.preferences = preferences;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferenceListView list(String app, Authentication authentication) {
        var normalizedApp = knownApp(app);
        var user = organization.currentUser(authentication);
        return new PreferenceListView(preferences.findByUserAndApp(user, normalizedApp).stream()
                .map(PreferenceView::from)
                .toList());
    }

    @Transactional
    public PreferenceView save(
            String reportKey,
            SavePreferenceRequest request,
            Authentication authentication) {
        var normalizedReportKey = required(reportKey, "reportKey");
        if (request == null) {
            throw new IllegalArgumentException("request es obligatorio");
        }
        var normalizedApp = knownApp(request.app());
        var user = organization.currentUser(authentication);
        var preference = preferences.findByUserAndAppAndReportKey(user, normalizedApp, normalizedReportKey)
                .orElseGet(() -> {
                    var created = new ReportVisualizationPreference(
                            user, normalizedApp, normalizedReportKey, request.visibleAttributes());
                    created.markCreated(clock.instant());
                    return created;
                });
        preference.updateVisibleAttributes(request.visibleAttributes(), clock.instant());
        return PreferenceView.from(preferences.save(preference));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    private static String knownApp(String value) {
        var normalized = required(value, "app");
        if (!normalized.equals("venta") && !normalized.equals("gestion")) {
            throw new IllegalArgumentException("app no soportada");
        }
        return normalized;
    }

    public record PreferenceListView(List<PreferenceView> preferences) {
    }

    public record PreferenceView(String reportKey, List<String> visibleAttributes) {
        static PreferenceView from(ReportVisualizationPreference preference) {
            return new PreferenceView(preference.getReportKey(), preference.getVisibleAttributes());
        }
    }

    public record SavePreferenceRequest(
            @NotBlank String app,
            @NotEmpty List<@NotBlank String> visibleAttributes) {
    }
}

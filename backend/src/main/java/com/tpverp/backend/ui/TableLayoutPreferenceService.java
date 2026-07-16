package com.tpverp.backend.ui;

import com.tpverp.backend.organization.CurrentOrganization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TableLayoutPreferenceService {

    public static final String APP_PATTERN = TableLayoutPreference.APP_REGEXP;
    public static final String TABLE_KEY_PATTERN = TableLayoutPreference.TABLE_KEY_REGEXP;

    private final TableLayoutPreferenceRepository preferences;
    private final CurrentOrganization organization;
    private final Clock clock;

    public TableLayoutPreferenceService(
            TableLayoutPreferenceRepository preferences,
            CurrentOrganization organization,
            Clock clock) {
        this.preferences = preferences;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferenceListView list(String app, Authentication authentication) {
        String normalizedApp = TableLayoutPreference.knownApp(app);
        var user = organization.currentUser(authentication);
        return new PreferenceListView(normalizedApp, preferences
                .findAllByUserAndAppOrderByTableKeyAsc(user, normalizedApp)
                .stream()
                .map(PreferenceView::from)
                .toList());
    }

    @Transactional(readOnly = true)
    public PreferenceView get(
            String app,
            String tableKey,
            Authentication authentication) {
        String normalizedApp = TableLayoutPreference.knownApp(app);
        String normalizedTableKey = TableLayoutPreference.knownTableKey(tableKey);
        var user = organization.currentUser(authentication);
        return preferences.findByUserAndAppAndTableKey(user, normalizedApp, normalizedTableKey)
                .map(PreferenceView::from)
                .orElseGet(() -> new PreferenceView(
                        normalizedApp, normalizedTableKey, List.of()));
    }

    @Transactional
    public PreferenceView save(
            String app,
            String tableKey,
            SavePreferenceRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        String normalizedApp = TableLayoutPreference.knownApp(app);
        String normalizedTableKey = TableLayoutPreference.knownTableKey(tableKey);
        if (!normalizedApp.equals(TableLayoutPreference.knownApp(request.app()))) {
            throw new IllegalArgumentException("app del cuerpo no coincide con el path");
        }
        if (!normalizedTableKey.equals(TableLayoutPreference.knownTableKey(request.tableKey()))) {
            throw new IllegalArgumentException("tableKey del cuerpo no coincide con el path");
        }
        List<TableLayoutColumn> columns = TableLayoutPreference.validateColumns(request.columns());
        var user = organization.currentUser(authentication);
        var preference = preferences
                .findByUserAndAppAndTableKey(user, normalizedApp, normalizedTableKey)
                .orElseGet(() -> new TableLayoutPreference(
                        user,
                        normalizedApp,
                        normalizedTableKey,
                        columns,
                        clock.instant()));
        preference.update(columns, clock.instant());
        return PreferenceView.from(preferences.save(preference));
    }

    public record PreferenceListView(String app, List<PreferenceView> preferences) {
        public PreferenceListView {
            app = TableLayoutPreference.knownApp(app);
            preferences = List.copyOf(preferences);
        }
    }

    public record PreferenceView(
            String app,
            String tableKey,
            List<TableLayoutColumn> columns) {

        public PreferenceView {
            columns = List.copyOf(columns);
        }

        static PreferenceView from(TableLayoutPreference preference) {
            return new PreferenceView(
                    preference.getApp(),
                    preference.getTableKey(),
                    preference.getColumns());
        }
    }

    public record SavePreferenceRequest(
            @NotBlank @Pattern(regexp = APP_PATTERN) String app,
            @NotBlank
            @Size(max = TableLayoutPreference.MAX_TABLE_KEY_LENGTH)
            @Pattern(regexp = TABLE_KEY_PATTERN)
            String tableKey,
            @NotNull
            @Size(max = TableLayoutPreference.MAX_COLUMNS)
            List<@NotNull @Valid TableLayoutColumn> columns) {
    }
}

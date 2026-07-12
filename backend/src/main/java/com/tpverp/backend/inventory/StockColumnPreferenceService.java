package com.tpverp.backend.inventory;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockColumnPreferenceService {

    public static final String APP_PATTERN = "(?i)venta|gestion";

    private final StockColumnPreferenceRepository preferences;
    private final CurrentOrganization organization;
    private final UserAccountRepository users;
    private final Clock clock;

    public StockColumnPreferenceService(
            StockColumnPreferenceRepository preferences,
            CurrentOrganization organization,
            UserAccountRepository users,
            Clock clock) {
        this.preferences = preferences;
        this.organization = organization;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferenceView get(String app, Authentication authentication) {
        String normalizedApp = StockColumnPreference.knownApp(app);
        Scope scope = currentScope(authentication);
        return preferences.findByCompanyIdAndStoreIdAndUserIdAndApp(
                        scope.companyId(), scope.storeId(), scope.userId(), normalizedApp)
                .map(PreferenceView::from)
                .orElseGet(() -> new PreferenceView(normalizedApp, Map.of()));
    }

    @Transactional
    public PreferenceView save(
            String app,
            SavePreferenceRequest request,
            Authentication authentication) {
        Objects.requireNonNull(request, "request");
        String normalizedApp = StockColumnPreference.knownApp(app);
        if (!normalizedApp.equals(StockColumnPreference.knownApp(request.app()))) {
            throw new IllegalArgumentException("app del cuerpo no coincide con el path");
        }
        Map<String, List<StockColumnSetting>> columns =
                StockColumnPreference.validateAndCopy(request.settings());
        Scope scope = currentScope(authentication);
        var existing = preferences.findByCompanyIdAndStoreIdAndUserIdAndApp(
                scope.companyId(), scope.storeId(), scope.userId(), normalizedApp);
        StockColumnPreference preference;
        if (existing.isPresent()) {
            preference = existing.get();
            preference.update(columns, clock.instant());
        } else {
            preference = new StockColumnPreference(
                    scope.companyId(),
                    scope.storeId(),
                    scope.userId(),
                    normalizedApp,
                    columns,
                    clock.instant());
        }
        return PreferenceView.from(preferences.save(preference));
    }

    private Scope currentScope(Authentication authentication) {
        var user = organization.currentUser(authentication);
        var store = organization.currentStore();
        var company = store == null ? null : organization.currentCompany();
        if (store == null || company == null
                || (!user.isProtegido()
                        && !users.hasStoreAccess(user.getId(), store.getId()))) {
            throw new AccessDeniedException("El usuario no tiene acceso a la tienda actual");
        }
        return new Scope(company.getId(), store.getId(), user.getId());
    }

    private record Scope(UUID companyId, UUID storeId, UUID userId) {
    }

    public record PreferenceView(
            String app,
            Map<String, List<StockColumnSetting>> settings) {

        static PreferenceView from(StockColumnPreference preference) {
            return new PreferenceView(preference.getApp(), preference.getColumns());
        }
    }

    public record SavePreferenceRequest(
            @NotBlank @Pattern(regexp = APP_PATTERN) String app,
            @NotEmpty @Size(max = 7)
            Map<
                    @NotBlank @Size(max = 64) String,
                    @NotEmpty @Size(max = 64) List<@Valid StockColumnSetting>> settings) {
    }
}

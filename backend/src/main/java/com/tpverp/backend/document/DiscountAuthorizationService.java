package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountAuthorizationService {

    private static final Duration VALIDITY = Duration.ofMinutes(5);

    private final UserAccountRepository users;
    private final CurrentOrganization organization;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, AuthorizationGrant> grants = new ConcurrentHashMap<>();

    public DiscountAuthorizationService(
            UserAccountRepository users,
            CurrentOrganization organization,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.users = users;
        this.organization = organization;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AuthorizationResult authorize(
            String managerName,
            String password,
            BigDecimal requestedPercent,
            Authentication authentication) {
        var percent = normalizePercent(requestedPercent);
        var manager = users.findByEmpresaIdAndNombre(
                        organization.currentCompany().getId(), normalizeName(managerName))
                .filter(UserAccount::isActivo)
                .orElseThrow(() -> new IllegalArgumentException("Responsable no encontrado o inactivo"));
        if (!passwordEncoder.matches(password == null ? "" : password, manager.getPasswordHash())) {
            throw new IllegalArgumentException("Credenciales del responsable incorrectas");
        }
        if (!canApplyDiscount(manager)) {
            throw new IllegalStateException("El responsable no tiene el permiso APLICAR_DESCUENTO");
        }
        if (manager.getMaxDiscountPercent().compareTo(percent) < 0) {
            throw new IllegalStateException("El limite del responsable no cubre el descuento solicitado");
        }
        var operator = requireUser(authentication);
        var token = newToken();
        var expiresAt = Instant.now(clock).plus(VALIDITY);
        grants.put(token, new AuthorizationGrant(
                operator.getId(), organization.currentStore().getId(), manager.getId(), percent, expiresAt));
        removeExpired();
        return new AuthorizationResult(token, manager.getNombre(), percent, expiresAt);
    }

    public void enforce(
            BigDecimal requestedPercent,
            String authorizationToken,
            Authentication authentication) {
        var percent = normalizePercent(requestedPercent);
        if (percent.signum() == 0) {
            return;
        }
        if (!PermissionChecks.hasRole(authentication, "ADMIN")
                && !PermissionChecks.hasAuthority(authentication, CorePermissionBootstrap.APLICAR_DESCUENTO)) {
            throw new IllegalStateException("No tienes el permiso APLICAR_DESCUENTO");
        }
        var user = requireUser(authentication);
        if (user.getMaxDiscountPercent().compareTo(percent) >= 0) {
            return;
        }
        var grant = authorizationToken == null ? null : grants.get(authorizationToken);
        if (grant == null
                || grant.expiresAt().isBefore(Instant.now(clock))
                || !grant.operatorId().equals(user.getId())
                || !grant.storeId().equals(organization.currentStore().getId())
                || grant.approvedPercent().compareTo(percent) < 0) {
            throw new IllegalStateException("Se requiere autorizacion vigente de un responsable");
        }
    }

    private boolean canApplyDiscount(UserAccount user) {
        return user.getRol().isProtegido() || user.getRol().getPermisos().stream()
                .anyMatch(value -> CorePermissionBootstrap.APLICAR_DESCUENTO
                        .equals(value.getPermiso().getCodigo()));
    }

    private static BigDecimal normalizePercent(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException("El descuento solicitado debe estar entre 0 y 100");
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El usuario responsable es obligatorio");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String newToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void removeExpired() {
        var now = Instant.now(clock);
        grants.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static UserAccount requireUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            return user;
        }
        throw new IllegalStateException("user_required");
    }

    private record AuthorizationGrant(
            UUID operatorId,
            UUID storeId,
            UUID managerId,
            BigDecimal approvedPercent,
            Instant expiresAt) {
    }

    public record AuthorizationResult(
            String token,
            String managerName,
            BigDecimal approvedPercent,
            Instant expiresAt) {
    }
}

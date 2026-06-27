package com.tpverp.backend.cash;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.util.Locale;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashPermissionService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentOrganization organization;

    public CashPermissionService(
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            CurrentOrganization organization) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.organization = organization;
    }

    // Exige permiso operativo de ventas o administracion total.
    public void requireSalesPermission(Authentication authentication) {
        if (!isAdmin(authentication)
                && !hasAuthority(authentication, CorePermissionBootstrap.GESTION_VENTAS)
                && !hasAuthority(authentication, CorePermissionBootstrap.CASH_OPERATE)) {
            throw new AccessDeniedException("Se requiere permiso de ventas o caja");
        }
    }

    // Permite consultar el estado de caja a perfiles operativos, contables o de solo lectura.
    public void requireCashStatusPermission(Authentication authentication) {
        if (!isAdmin(authentication)
                && !hasAuthority(authentication, CorePermissionBootstrap.GESTION_VENTAS)
                && !hasAuthority(authentication, CorePermissionBootstrap.CASH_OPERATE)
                && !hasAuthority(authentication, CorePermissionBootstrap.GESTION_CUENTAS)
                && !hasAuthority(authentication, CorePermissionBootstrap.CASH_READ)) {
            throw new AccessDeniedException("Se requiere permiso de consulta de caja");
        }
    }

    // Exige permiso contable o administracion total.
    public void requireAccountingPermission(Authentication authentication) {
        if (!isAdmin(authentication)
                && !hasAuthority(authentication, CorePermissionBootstrap.GESTION_CUENTAS)) {
            throw new AccessDeniedException("Se requiere permiso de gestion de cuentas");
        }
    }

    public void requireReportPermission(Authentication authentication) {
        if (!isAdmin(authentication)
                && !hasAuthority(authentication, CorePermissionBootstrap.GESTION_CUENTAS)
                && !hasAuthority(authentication, CorePermissionBootstrap.CASH_READ)) {
            throw new AccessDeniedException("Se requiere permiso de informes de caja");
        }
    }

    public void requireConfigPermission(Authentication authentication) {
        if (!isAdmin(authentication)
                && !hasAuthority(authentication, CorePermissionBootstrap.GESTION_CUENTAS)
                && !hasAuthority(authentication, CorePermissionBootstrap.CASH_CONFIGURE)) {
            throw new AccessDeniedException("Se requiere permiso de configuracion de caja");
        }
    }

    // Indica si el usuario puede ver importes teoricos y disponibles.
    public boolean canSeeExpectedTotals(Authentication authentication) {
        return isAdmin(authentication)
                || hasAuthority(authentication, CorePermissionBootstrap.GESTION_CUENTAS);
    }

    // Valida credenciales de un autorizador activo con perfil ADMIN o contable.
    @Transactional(readOnly = true)
    public UserAccount requireAuthorizer(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Credenciales de autorizador obligatorias");
        }
        var store = organization.currentStore();
        var normalizedName = username.trim().toUpperCase(Locale.ROOT);
        var user = users.findByTiendaIdAndNombre(store.getId(), normalizedName)
                .filter(UserAccount::isActivo)
                .orElseThrow(() -> new IllegalArgumentException("Autorizador no valido"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Autorizador no valido");
        }
        if (!isAdmin(user) && !roleHasPermission(user, CorePermissionBootstrap.GESTION_CUENTAS)) {
            throw new IllegalArgumentException("El autorizador debe ser ADMIN o contable");
        }
        return user;
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        if (authentication.getPrincipal() instanceof UserAccount user && isAdmin(user)) {
            return true;
        }
        return "ADMIN".equalsIgnoreCase(authentication.getName())
                || hasAuthority(authentication, "ROLE_ADMIN");
    }

    private boolean isAdmin(UserAccount user) {
        return "ADMIN".equalsIgnoreCase(user.getNombre())
                || "ROLE_ADMIN".equals(user.getRol().authority());
    }

    private boolean roleHasPermission(UserAccount user, String permission) {
        return user.getRol().getPermisos().stream()
                .anyMatch(rolePermission -> permission.equals(rolePermission.getPermiso().getCodigo()));
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}

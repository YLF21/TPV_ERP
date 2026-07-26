package com.tpverp.backend.security.application;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.util.Locale;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalPermissionAuthorizationService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentOrganization organization;

    public OperationalPermissionAuthorizationService(
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            CurrentOrganization organization) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public Authorization authorize(
            String permission,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var operator = organization.currentUser(authentication);
        if (PermissionChecks.hasRole(authentication, "ADMIN")
                || PermissionChecks.hasAuthority(authentication, permission)) {
            return new Authorization(operator, operator, false);
        }
        if (authorizerUsername == null || authorizerUsername.isBlank()
                || authorizerPassword == null || authorizerPassword.isBlank()) {
            throw new AccessDeniedException("Se requiere autorizacion de un usuario con permiso " + permission);
        }
        var normalizedName = authorizerUsername.trim().toUpperCase(Locale.ROOT);
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var authorizer = users.findByEmpresaIdAndNombre(companyId, normalizedName)
                .or(() -> users.findByNombreAndTiendaIsNull(normalizedName)
                        .filter(UserAccount::isProtegido))
                .filter(UserAccount::isActivo)
                .filter(user -> user.isProtegido() || users.hasStoreAccess(user.getId(), storeId))
                .orElseThrow(() -> new IllegalArgumentException("Usuario autorizador no valido"));
        if (!passwordEncoder.matches(authorizerPassword, authorizer.getPasswordHash())) {
            throw new IllegalArgumentException("Usuario autorizador no valido");
        }
        if (!hasPermission(authorizer, permission)) {
            throw new AccessDeniedException("El usuario autorizador no tiene el permiso " + permission);
        }
        return new Authorization(operator, authorizer, !operator.getId().equals(authorizer.getId()));
    }

    private static boolean hasPermission(UserAccount user, String permission) {
        return user.isProtegido()
                || user.getRol().isProtegido()
                || user.getRol().getPermisos().stream()
                        .anyMatch(rolePermission -> permission.equals(
                                rolePermission.getPermiso().getCodigo()));
    }

    public record Authorization(
            UserAccount operator,
            UserAccount authorizer,
            boolean delegated) {
    }
}

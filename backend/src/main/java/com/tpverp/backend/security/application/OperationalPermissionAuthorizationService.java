package com.tpverp.backend.security.application;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.util.Locale;
import java.util.Set;
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

    /**
     * Reauthenticates every sensitive operation, including operations performed
     * by a user who already owns one of the required permissions.
     */
    @Transactional(readOnly = true)
    public Authorization authorizeWithPassword(
            Set<String> permissions,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un permiso");
        }
        if (authorizerPassword == null || authorizerPassword.isBlank()) {
            throw new AccessDeniedException("La contraseña de autorización es obligatoria");
        }
        var operator = organization.currentUser(authentication);
        if (PermissionChecks.hasRole(authentication, "ADMIN")
                || permissions.stream().anyMatch(permission ->
                        PermissionChecks.hasAuthority(authentication, permission))) {
            if (!passwordEncoder.matches(authorizerPassword, operator.getPasswordHash())) {
                throw new IllegalArgumentException("Contraseña incorrecta");
            }
            return new Authorization(operator, operator, false);
        }
        if (authorizerUsername == null || authorizerUsername.isBlank()) {
            throw new AccessDeniedException(
                    "Se requiere un usuario con permiso para autorizar la operación");
        }
        var normalizedName = authorizerUsername.trim().toUpperCase(Locale.ROOT);
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var authorizer = users.findByEmpresaIdAndNombre(companyId, normalizedName)
                .or(() -> users.findByNombreAndTiendaIsNull(normalizedName)
                        .filter(UserAccount::isProtegido))
                .filter(UserAccount::isActivo)
                .filter(user -> user.isProtegido() || users.hasStoreAccess(user.getId(), storeId))
                .orElseThrow(() -> new IllegalArgumentException("Usuario autorizador no válido"));
        if (!passwordEncoder.matches(authorizerPassword, authorizer.getPasswordHash())) {
            throw new IllegalArgumentException("Usuario autorizador no válido");
        }
        if (permissions.stream().noneMatch(permission -> hasPermission(authorizer, permission))) {
            throw new AccessDeniedException(
                    "El usuario autorizador no tiene permisos de gestión de ventas o cuentas");
        }
        return new Authorization(operator, authorizer, !operator.getId().equals(authorizer.getId()));
    }

    /**
     * Resolves the four configurable operational-security combinations.
     *
     * <ul>
     *   <li>P0/W0: the authenticated operator executes directly.</li>
     *   <li>P0/W1: the current operator must reauthenticate; delegation is not allowed.</li>
     *   <li>P1/W0: a permitted operator executes directly, otherwise an authorised
     *       user delegates with username and password.</li>
     *   <li>P1/W1: a permitted operator reauthenticates, otherwise an authorised
     *       user delegates with username and password.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Authorization authorize(
            Set<String> permissions,
            boolean requirePermission,
            boolean requirePassword,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var requiredPermissions = permissions == null ? Set.<String>of() : Set.copyOf(permissions);
        if (requiredPermissions.stream().anyMatch(
                permission -> permission == null || permission.isBlank())) {
            throw new IllegalArgumentException("Los permisos no pueden estar vacios");
        }
        if (requirePermission && requiredPermissions.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un permiso");
        }

        var operator = organization.currentUser(authentication);
        var operatorHasPermission = !requirePermission
                || PermissionChecks.hasRole(authentication, "ADMIN")
                || requiredPermissions.stream().anyMatch(
                        permission -> PermissionChecks.hasAuthority(authentication, permission));

        if (operatorHasPermission) {
            if (requirePassword) {
                requireCurrentPassword(operator, authorizerPassword);
            }
            return new Authorization(operator, operator, false);
        }

        if (authorizerUsername == null || authorizerUsername.isBlank()
                || authorizerPassword == null || authorizerPassword.isBlank()) {
            throw new AccessDeniedException(
                    "Se requiere usuario y contrasena de un autorizador con permiso");
        }
        var authorizer = delegatedAuthorizer(authorizerUsername, authorizerPassword);
        if (requiredPermissions.stream().noneMatch(
                permission -> hasPermission(authorizer, permission))) {
            throw new AccessDeniedException(
                    "El usuario autorizador no tiene ninguno de los permisos requeridos");
        }
        return new Authorization(
                operator,
                authorizer,
                !operator.getId().equals(authorizer.getId()));
    }

    /**
     * Explicitly authenticates the named authorizer even when the current
     * operator already owns one of the permissions.
     *
     * <p>This is used for percentage limits: an operator may own the discount
     * permission but still need a manager whose personal limit covers the
     * requested discount.</p>
     */
    @Transactional(readOnly = true)
    public Authorization authorizeNamed(
            Set<String> permissions,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var requiredPermissions = permissions == null ? Set.<String>of() : Set.copyOf(permissions);
        if (requiredPermissions.isEmpty()
                || requiredPermissions.stream().anyMatch(
                        permission -> permission == null || permission.isBlank())) {
            throw new IllegalArgumentException("Se requiere al menos un permiso");
        }
        if (authorizerUsername == null || authorizerUsername.isBlank()
                || authorizerPassword == null || authorizerPassword.isBlank()) {
            throw new AccessDeniedException(
                    "Se requiere usuario y contrasena de un autorizador con permiso");
        }
        var operator = organization.currentUser(authentication);
        var authorizer = delegatedAuthorizer(authorizerUsername, authorizerPassword);
        if (requiredPermissions.stream().noneMatch(
                permission -> hasPermission(authorizer, permission))) {
            throw new AccessDeniedException(
                    "El usuario autorizador no tiene ninguno de los permisos requeridos");
        }
        return new Authorization(
                operator,
                authorizer,
                !operator.getId().equals(authorizer.getId()));
    }

    private void requireCurrentPassword(UserAccount operator, String password) {
        if (password == null || password.isBlank()) {
            throw new AccessDeniedException("La contrasena de autorizacion es obligatoria");
        }
        if (!passwordEncoder.matches(password, operator.getPasswordHash())) {
            throw new IllegalArgumentException("Contrasena incorrecta");
        }
    }

    private UserAccount delegatedAuthorizer(String username, String password) {
        var normalizedName = username.trim().toUpperCase(Locale.ROOT);
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var authorizer = users.findByEmpresaIdAndNombre(companyId, normalizedName)
                .or(() -> users.findByNombreAndTiendaIsNull(normalizedName)
                        .filter(UserAccount::isProtegido))
                .filter(UserAccount::isActivo)
                .filter(user -> user.isProtegido() || users.hasStoreAccess(user.getId(), storeId))
                .orElseThrow(() -> new IllegalArgumentException("Usuario autorizador no valido"));
        if (!passwordEncoder.matches(password, authorizer.getPasswordHash())) {
            throw new IllegalArgumentException("Usuario autorizador no valido");
        }
        return authorizer;
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

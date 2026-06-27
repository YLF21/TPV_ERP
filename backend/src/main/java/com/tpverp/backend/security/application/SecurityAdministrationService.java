package com.tpverp.backend.security.application;

import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.PermissionRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.RoleRepository;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import java.util.Map;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.BackupSettingsRepository;
import com.tpverp.backend.installation.InstallationRepository;
import java.nio.file.Path;

public class SecurityAdministrationService {

    private final CurrentOrganization organization;
    private final UserAccountRepository usuarioRepository;
    private final RoleRepository rolRepository;
    private final PermissionRepository permisoRepository;
    private final UserSessionRepository sesionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuditService auditService;
    private final BackupKeyStore backupKeyStore;
    private final BackupSettingsRepository backupConfigurationRepository;
    private final InstallationRepository installationRepository;

    public SecurityAdministrationService(
            CurrentOrganization organization,
            UserAccountRepository usuarioRepository,
            RoleRepository rolRepository,
            PermissionRepository permisoRepository,
            UserSessionRepository sesionRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuditService auditService,
            BackupKeyStore backupKeyStore,
            BackupSettingsRepository backupConfigurationRepository,
            InstallationRepository installationRepository) {
        this.organization = organization;
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.permisoRepository = permisoRepository;
        this.sesionRepository = sesionRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.auditService = auditService;
        this.backupKeyStore = backupKeyStore;
        this.backupConfigurationRepository = backupConfigurationRepository;
        this.installationRepository = installationRepository;
    }

    @Transactional
    public UserItem createUser(String name, String password, UUID roleId) {
        Store store = currentStore();
        String normalized = normalize(name);
        if (usuarioRepository.findByTiendaIdAndNombre(store.getId(), normalized).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese usuario");
        }
        Role role = role(roleId);
        requireAssignable(role);
        UserAccount user = usuarioRepository.save(
                new UserAccount(store, normalized, passwordEncoder.encode(password), role));
        auditService.record(
                "USER_CREATED", AuditResult.EXITO, Map.of("userId", user.getId()));
        return UserItem.from(user);
    }

    @Transactional
    public void setUserActive(UUID userId, boolean active) {
        UserAccount user = user(userId);
        if (active) {
            user.activate();
            return;
        }
        user.deactivate();
        Instant now = Instant.now(clock);
        sesionRepository.findByUsuarioIdAndRevocadaEnIsNull(userId)
                .forEach(session -> session.revocar(user, "USER_DISABLED", now));
        auditService.record(
                "USER_DISABLED", AuditResult.EXITO, Map.of("userId", userId));
    }

    @Transactional
    public UserItem changeUserRole(UUID userId, UUID roleId) {
        UserAccount user = user(userId);
        var role = role(roleId);
        requireAssignable(role);
        user.cambiarRol(role);
        auditService.record(
                "USER_ROLE_CHANGED",
                AuditResult.EXITO,
                Map.of("userId", userId, "roleId", roleId));
        return UserItem.from(user);
    }

    @Transactional(readOnly = true)
    public List<UserItem> users() {
        return usuarioRepository.findAllByTiendaIdOrderByNombre(currentStore().getId())
                .stream().map(UserItem::from).toList();
    }

    @Transactional
    public void changeAdminPassword(String currentPassword, String newPassword) {
        Store store = currentStore();
        UserAccount admin = usuarioRepository.findByTiendaIdAndNombre(store.getId(), "ADMIN")
                .orElseThrow(() -> new IllegalStateException("El usuario ADMIN no existe"));
        if (!passwordEncoder.matches(currentPassword, admin.getPasswordHash())) {
            throw new IllegalArgumentException("La contrasena ADMIN actual no es valida");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("La nueva contrasena debe tener al menos 8 caracteres");
        }
        var installation = installationRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
        var backupConfiguration = backupConfigurationRepository
                .findByInstalacionId(installation.getId())
                .orElseThrow(() -> new IllegalStateException("El backup no esta configurado"));
        Path backupDirectory = Path.of(backupConfiguration.getDestino().get("path").toString());
        backupKeyStore.rewrap(
                currentPassword.toCharArray(),
                newPassword.toCharArray(),
                backupDirectory);
        admin.cambiarPassword(passwordEncoder.encode(newPassword));
        Instant now = Instant.now(clock);
        sesionRepository.findByUsuarioIdAndRevocadaEnIsNull(admin.getId())
                .forEach(session -> session.revocar(admin, "ADMIN_PASSWORD_CHANGED", now));
        auditService.record(
                "ADMIN_PASSWORD_CHANGED",
                AuditResult.EXITO,
                Map.of("userId", admin.getId()));
    }

    @Transactional
    public RoleItem createRole(String name) {
        Store store = currentStore();
        String normalized = normalize(name);
        if (rolRepository.findByTiendaIdAndNombre(store.getId(), normalized).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese rol");
        }
        Role role = rolRepository.save(new Role(store, normalized));
        auditService.record(
                "ROLE_CREATED", AuditResult.EXITO, Map.of("roleId", role.getId()));
        return RoleItem.from(role);
    }

    @Transactional
    public RoleItem assignPermissions(UUID roleId, Set<String> permissionCodes) {
        Role role = role(roleId);
        var permissions = permissionCodes.stream()
                .map(this::permission)
                .collect(Collectors.toSet());
        role.replacePermissions(permissions);
        auditService.record(
                "ROLE_PERMISSIONS_CHANGED",
                AuditResult.EXITO,
                Map.of("roleId", roleId, "permissions", permissionCodes));
        return RoleItem.from(role);
    }

    @Transactional(readOnly = true)
    public List<RoleItem> roles() {
        return rolRepository.findAllByTiendaIdOrderByNombre(currentStore().getId())
                .stream().map(RoleItem::from).toList();
    }

    private UserAccount user(UUID id) {
        return usuarioRepository.findByIdAndTiendaId(id, currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("UserAccount no encontrado"));
    }

    private Role role(UUID id) {
        return rolRepository.findByIdAndTiendaId(id, currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("Role no encontrado"));
    }

    private void requireAssignable(Role role) {
        if (role.isProtegido()) {
            throw new IllegalStateException(
                    "El rol ADMIN no se puede asignar a otros usuarios");
        }
    }

    private com.tpverp.backend.security.domain.Permission permission(String code) {
        return permisoRepository.findByCodigo(normalize(code))
                .orElseThrow(() -> new IllegalArgumentException("Permission no encontrado: " + code));
    }

    private Store currentStore() {
        return organization.currentStore();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El valor es obligatorio");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record UserItem(UUID id, String name, String role, boolean active, boolean protectedUser) {
        static UserItem from(UserAccount user) {
            return new UserItem(
                    user.getId(), user.getNombre(), user.getRol().getNombre(),
                    user.isActivo(), user.isProtegido());
        }
    }

    public record RoleItem(UUID id, String name, boolean protectedRole, Set<String> permissions) {
        static RoleItem from(Role role) {
            Set<String> permissions = role.isProtegido()
                    ? Set.of("ALL")
                    : role.getPermisos().stream()
                            .map(value -> value.getPermiso().getCodigo())
                            .collect(Collectors.toUnmodifiableSet());
            return new RoleItem(role.getId(), role.getNombre(), role.isProtegido(), permissions);
        }
    }
}

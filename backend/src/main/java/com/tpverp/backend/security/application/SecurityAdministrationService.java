package com.tpverp.backend.security.application;

import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import java.util.Map;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.BackupSettingsRepository;
import com.tpverp.backend.installation.InstallationRepository;
import java.nio.file.Path;

public class SecurityAdministrationService {

    private static final String NUMERIC_PASSWORD_PATTERN = "\\d{4,12}";

    private final CurrentOrganization organization;
    private final UserAccountRepository usuarioRepository;
    private final RoleRepository rolRepository;
    private final PermissionRepository permisoRepository;
    private final UserSessionRepository sesionRepository;
    private final StoreRepository storeRepository;
    private final JdbcTemplate jdbc;
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
            StoreRepository storeRepository,
            JdbcTemplate jdbc,
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
        this.storeRepository = storeRepository;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.auditService = auditService;
        this.backupKeyStore = backupKeyStore;
        this.backupConfigurationRepository = backupConfigurationRepository;
        this.installationRepository = installationRepository;
    }

    @Transactional
    public UserItem createUser(String name, String password, UUID roleId) {
        return createUser(name, name, password, roleId);
    }

    @Transactional
    public UserItem createUser(String name, String userName, String password, UUID roleId) {
        Store store = currentStore();
        String normalized = normalize(name);
        requireNumericPassword(password);
        if (usuarioRepository.findByEmpresaIdAndNombre(
                organization.currentCompany().getId(), normalized).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese usuario");
        }
        Role role = role(roleId);
        requireAssignable(role);
        UserAccount user = new UserAccount(store, normalized, passwordEncoder.encode(password), role);
        user.cambiarUserName(userName);
        user = usuarioRepository.saveAndFlush(user);
        grantStoreAccess(user.getId(), Set.of(store.getId()), false);
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

    @Transactional
    public UserItem changeUserName(UUID userId, String userName) {
        UserAccount user = user(userId);
        requireMutableUser(user);
        user.cambiarUserName(userName);
        auditService.record(
                "USER_NAME_CHANGED", AuditResult.EXITO, Map.of("userId", userId));
        return UserItem.from(user);
    }

    @Transactional
    public UserItem changeUserIdentity(UUID userId, String name, String userName) {
        UserAccount user = user(userId);
        requireMutableUser(user);
        user.renombrar(name);
        user.cambiarUserName(userName);
        auditService.record(
                "USER_IDENTITY_CHANGED", AuditResult.EXITO, Map.of("userId", userId));
        return UserItem.from(user);
    }

    @Transactional
    public void resetPassword(UUID userId, String newPassword) {
        requireNumericPassword(newPassword);
        UserAccount user = user(userId);
        requireMutableUser(user);
        user.cambiarPassword(passwordEncoder.encode(newPassword));
        Instant now = Instant.now(clock);
        sesionRepository.findByUsuarioIdAndRevocadaEnIsNull(userId)
                .forEach(session -> session.revocar(user, "PASSWORD_RESET", now));
        auditService.record(
                "USER_PASSWORD_RESET", AuditResult.EXITO, Map.of("userId", userId));
    }

    @Transactional
    public UserItem replaceStoreAccess(UUID userId, Set<UUID> storeIds) {
        UserAccount user = user(userId);
        grantStoreAccess(user.getId(), storeIds, true);
        auditService.record(
                "USER_STORE_ACCESS_CHANGED",
                AuditResult.EXITO,
                Map.of("userId", userId, "storeIds", storeIds));
        return UserItem.from(user);
    }

    @Transactional(readOnly = true)
    public List<UserItem> users() {
        return usuarioRepository.findAllByEmpresaIdOrderByNombre(organization.currentCompany().getId())
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
        requireNumericPassword(newPassword);
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
    public RoleItem renameRole(UUID roleId, String name) {
        Role role = role(roleId);
        String normalized = normalize(name);
        if (role.getNombre().equals(normalized)) {
            return RoleItem.from(role);
        }
        if (rolRepository.findByTiendaIdAndNombre(currentStore().getId(), normalized).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese rol");
        }
        String previousName = role.getNombre();
        role.renombrar(normalized);
        auditService.record(
                "ROLE_RENAMED",
                AuditResult.EXITO,
                Map.of("roleId", roleId, "previousName", previousName, "newName", normalized));
        return RoleItem.from(role);
    }

    @Transactional
    public void deleteRole(UUID roleId) {
        Role role = role(roleId);
        role.validateDeletion();
        long assignedUsers = usuarioRepository.countByRolId(roleId);
        if (assignedUsers > 0) {
            throw new RoleInUseException(assignedUsers);
        }
        rolRepository.delete(role);
        auditService.record(
                "ROLE_DELETED",
                AuditResult.EXITO,
                Map.of("roleId", roleId, "name", role.getNombre()));
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

    @Transactional(readOnly = true)
    public List<RoleOption> roleOptions() {
        return rolRepository.findAllByTiendaIdAndProtegidoFalseOrderByNombre(currentStore().getId())
                .stream().map(RoleOption::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionItem> permissionCatalog() {
        return permisoRepository.findAllByOrderByGrupoAscCodigoAsc().stream()
                .map(PermissionItem::from)
                .toList();
    }

    private UserAccount user(UUID id) {
        return usuarioRepository.findByIdAndEmpresaId(id, organization.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.security.user_not_found"));
    }

    private void grantStoreAccess(UUID userId, Set<UUID> storeIds, boolean replace) {
        if (storeIds == null || storeIds.isEmpty()) {
            throw new IllegalArgumentException("message.security.store_access_required");
        }
        var companyId = organization.currentCompany().getId();
        var allowed = storeRepository.findByEmpresaId(companyId).stream()
                .map(Store::getId)
                .collect(Collectors.toSet());
        if (!allowed.containsAll(storeIds)) {
            throw new IllegalArgumentException("message.security.store_not_in_company");
        }
        if (replace) {
            jdbc.update("delete from usuario_tienda where usuario_id = ?", userId);
        }
        storeIds.forEach(storeId -> jdbc.update("""
                insert into usuario_tienda (usuario_id, tienda_id)
                values (?, ?)
                on conflict do nothing
                """, userId, storeId));
    }

    private Role role(UUID id) {
        return rolRepository.findByIdAndTiendaId(id, currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException("message.security.role_not_found"));
    }

    private void requireAssignable(Role role) {
        if (role.isProtegido()) {
            throw new IllegalStateException(
                    "El rol ADMIN no se puede asignar a otros usuarios");
        }
    }

    private com.tpverp.backend.security.domain.Permission permission(String code) {
        return permisoRepository.findByCodigo(normalize(code))
                .orElseThrow(() -> new IllegalArgumentException("message.security.permission_not_found"));
    }

    private Store currentStore() {
        return organization.currentStore();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message.common.value_required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void requireMutableUser(UserAccount user) {
        if (user.isProtegido()) {
            throw new IllegalStateException(
                    "El usuario ADMIN solo puede modificarse mediante las operaciones protegidas de administrador");
        }
    }

    private void requireNumericPassword(String value) {
        if (value == null || !value.matches(NUMERIC_PASSWORD_PATTERN)) {
            throw new IllegalArgumentException("La contrasena debe tener entre 4 y 12 cifras numericas");
        }
    }

    public record UserItem(
            UUID id, String userId, String name, String userName,
            String role, boolean active, boolean protectedUser) {
        static UserItem from(UserAccount user) {
            return new UserItem(
                    user.getId(), user.getUserId(), user.getNombre(), user.getUserName(), user.getRol().getNombre(),
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

    public record RoleOption(UUID id, String name) {
        static RoleOption from(Role role) {
            return new RoleOption(role.getId(), role.getNombre());
        }
    }

    public record PermissionItem(String code, String translationKey, String group) {
        static PermissionItem from(com.tpverp.backend.security.domain.Permission permission) {
            return new PermissionItem(
                    permission.getCodigo(), permission.getTranslationKey(), permission.getGrupo());
        }
    }
}

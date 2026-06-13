package com.tpverp.backend.security.application;

import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.PermisoRepository;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.RolRepository;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
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
import com.tpverp.backend.audit.ResultadoAuditoria;
import java.util.Map;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.ConfiguracionBackupRepository;
import com.tpverp.backend.installation.InstalacionRepository;
import java.nio.file.Path;

public class SecurityAdministrationService {

    private final TiendaRepository tiendaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final PermisoRepository permisoRepository;
    private final SesionRepository sesionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuditService auditService;
    private final BackupKeyStore backupKeyStore;
    private final ConfiguracionBackupRepository backupConfigurationRepository;
    private final InstalacionRepository installationRepository;

    public SecurityAdministrationService(
            TiendaRepository tiendaRepository,
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            PermisoRepository permisoRepository,
            SesionRepository sesionRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuditService auditService,
            BackupKeyStore backupKeyStore,
            ConfiguracionBackupRepository backupConfigurationRepository,
            InstalacionRepository installationRepository) {
        this.tiendaRepository = tiendaRepository;
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
        Tienda store = currentStore();
        String normalized = normalize(name);
        if (usuarioRepository.findByTiendaIdAndNombre(store.getId(), normalized).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese usuario");
        }
        Rol role = role(roleId);
        requireAssignable(role);
        Usuario user = usuarioRepository.save(
                new Usuario(store, normalized, passwordEncoder.encode(password), role));
        auditService.record(
                "USER_CREATED", ResultadoAuditoria.EXITO, Map.of("userId", user.getId()));
        return UserItem.from(user);
    }

    @Transactional
    public void setUserActive(UUID userId, boolean active) {
        Usuario user = user(userId);
        if (active) {
            user.activar();
            return;
        }
        user.desactivar();
        Instant now = Instant.now(clock);
        sesionRepository.findByUsuarioIdAndRevocadaEnIsNull(userId)
                .forEach(session -> session.revocar(user, "USER_DISABLED", now));
        auditService.record(
                "USER_DISABLED", ResultadoAuditoria.EXITO, Map.of("userId", userId));
    }

    @Transactional
    public UserItem changeUserRole(UUID userId, UUID roleId) {
        Usuario user = user(userId);
        var role = role(roleId);
        requireAssignable(role);
        user.cambiarRol(role);
        auditService.record(
                "USER_ROLE_CHANGED",
                ResultadoAuditoria.EXITO,
                Map.of("userId", userId, "roleId", roleId));
        return UserItem.from(user);
    }

    @Transactional(readOnly = true)
    public List<UserItem> users() {
        return usuarioRepository.findAll().stream().map(UserItem::from).toList();
    }

    @Transactional
    public void changeAdminPassword(String currentPassword, String newPassword) {
        Tienda store = currentStore();
        Usuario admin = usuarioRepository.findByTiendaIdAndNombre(store.getId(), "ADMIN")
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
                ResultadoAuditoria.EXITO,
                Map.of("userId", admin.getId()));
    }

    @Transactional
    public RoleItem createRole(String name) {
        Tienda store = currentStore();
        String normalized = normalize(name);
        if (rolRepository.findByTiendaIdAndNombre(store.getId(), normalized).isPresent()) {
            throw new IllegalArgumentException("Ya existe ese rol");
        }
        Rol role = rolRepository.save(new Rol(store, normalized));
        auditService.record(
                "ROLE_CREATED", ResultadoAuditoria.EXITO, Map.of("roleId", role.getId()));
        return RoleItem.from(role);
    }

    @Transactional
    public RoleItem assignPermissions(UUID roleId, Set<String> permissionCodes) {
        Rol role = role(roleId);
        var permissions = permissionCodes.stream()
                .map(this::permission)
                .collect(Collectors.toSet());
        role.reemplazarPermisos(permissions);
        auditService.record(
                "ROLE_PERMISSIONS_CHANGED",
                ResultadoAuditoria.EXITO,
                Map.of("roleId", roleId, "permissions", permissionCodes));
        return RoleItem.from(role);
    }

    @Transactional(readOnly = true)
    public List<RoleItem> roles() {
        return rolRepository.findAll().stream().map(RoleItem::from).toList();
    }

    private Usuario user(UUID id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
    }

    private Rol role(UUID id) {
        return rolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rol no encontrado"));
    }

    private void requireAssignable(Rol role) {
        if (role.isProtegido()) {
            throw new IllegalStateException(
                    "El rol ADMIN no se puede asignar a otros usuarios");
        }
    }

    private com.tpverp.backend.security.domain.Permiso permission(String code) {
        return permisoRepository.findByCodigo(normalize(code))
                .orElseThrow(() -> new IllegalArgumentException("Permiso no encontrado: " + code));
    }

    private Tienda currentStore() {
        return tiendaRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La tienda no esta inicializada"));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El valor es obligatorio");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record UserItem(UUID id, String name, String role, boolean active, boolean protectedUser) {
        static UserItem from(Usuario user) {
            return new UserItem(
                    user.getId(), user.getNombre(), user.getRol().getNombre(),
                    user.isActivo(), user.isProtegido());
        }
    }

    public record RoleItem(UUID id, String name, boolean protectedRole, Set<String> permissions) {
        static RoleItem from(Rol role) {
            Set<String> permissions = role.isProtegido()
                    ? Set.of("ALL")
                    : role.getPermisos().stream()
                            .map(value -> value.getPermiso().getCodigo())
                            .collect(Collectors.toUnmodifiableSet());
            return new RoleItem(role.getId(), role.getNombre(), role.isProtegido(), permissions);
        }
    }
}

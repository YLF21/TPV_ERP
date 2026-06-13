package com.tpverp.backend.security.application;

import com.tpverp.backend.security.domain.Permiso;
import com.tpverp.backend.security.domain.PermisoRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class CorePermissionBootstrap {

    public static final String USERS_MANAGE = "USERS_MANAGE";
    public static final String ROLES_MANAGE = "ROLES_MANAGE";
    public static final String TERMINALS_MANAGE = "TERMINALS_MANAGE";
    public static final String LICENSES_MANAGE = "LICENSES_MANAGE";
    public static final String BACKUPS_MANAGE = "BACKUPS_MANAGE";
    public static final String AUDIT_READ = "AUDIT_READ";
    private final PermisoRepository permisoRepository;

    public CorePermissionBootstrap(PermisoRepository permisoRepository) {
        this.permisoRepository = permisoRepository;
    }

    @Transactional
    public void initialize() {
        List.of(
                permission(USERS_MANAGE, "security.permissions.users", "SECURITY"),
                permission(ROLES_MANAGE, "security.permissions.roles", "SECURITY"),
                permission(TERMINALS_MANAGE, "security.permissions.terminals", "SECURITY"),
                permission(LICENSES_MANAGE, "security.permissions.licenses", "SYSTEM"),
                permission(BACKUPS_MANAGE, "security.permissions.backups", "SYSTEM"),
                permission(AUDIT_READ, "security.permissions.audit", "SYSTEM"))
                .forEach(permission -> permisoRepository.findByCodigo(permission.getCodigo())
                        .orElseGet(() -> permisoRepository.save(permission)));
    }

    private Permiso permission(String code, String translationKey, String group) {
        return new Permiso(code, translationKey, group);
    }
}

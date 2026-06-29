package com.tpverp.frontend.venta;

import com.tpverp.frontend.common.security.Permission;

import java.util.Map;
import java.util.Set;

final class LocalLoginService {

    private static final Map<String, LocalUser> USERS = Map.of(
            "admin", new LocalUser("admin", "admin", Set.of(Permission.ADMIN)),
            "venta", new LocalUser("venta", "venta", Set.of(Permission.VENTA)),
            "gestor", new LocalUser("gestor", "gestor", Set.of(Permission.GESTION_VENTAS)),
            "producto", new LocalUser("producto", "producto", Set.of(Permission.GESTION_PRODUCTO))
    );

    LocalLoginResult login(String userName, String password) {
        LocalUser user = USERS.get(normalize(userName));
        if (user == null || !user.password.equals(password)) {
            return new LocalLoginResult(false, "", Set.of());
        }
        return new LocalLoginResult(true, user.userName, user.permissions);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    // ponytail: local users only until backend session exposes frontend permissions.
    private record LocalUser(String userName, String password, Set<Permission> permissions) {
    }
}

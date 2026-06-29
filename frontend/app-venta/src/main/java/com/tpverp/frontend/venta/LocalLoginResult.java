package com.tpverp.frontend.venta;

import com.tpverp.frontend.common.security.Permission;
import com.tpverp.frontend.common.security.PermissionRules;

import java.util.Set;

public record LocalLoginResult(boolean authenticated, String userName, Set<Permission> permissions) {

    public boolean canEnterAppVenta() {
        return authenticated && PermissionRules.canEnterAppVenta(permissions);
    }
}

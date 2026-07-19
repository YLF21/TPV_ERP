package com.tpverp.backend.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class SecurityAdministrationControllerContractTest {

    @Test
    void separatesUserManagementFromRoleManagement() throws NoSuchMethodException {
        assertThat(permission("users"))
                .contains("GESTION_USUARIO")
                .doesNotContain("ROLES_MANAGE");
        assertThat(permission("roles"))
                .contains("ROLES_MANAGE")
                .doesNotContain("GESTION_USUARIO");
        assertThat(permission(
                "assignPermissions", UUID.class, SecurityAdministrationController.PermissionsRequest.class))
                .contains("ROLES_MANAGE")
                .doesNotContain("GESTION_USUARIO");
    }

    private String permission(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = SecurityAdministrationController.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(PreAuthorize.class).value();
    }
}

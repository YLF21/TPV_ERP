package com.tpverp.saas.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final SaasAdminUserRepository users;
    private final AdminPasswordHasher passwords;

    public AdminAuthInterceptor(SaasAdminUserRepository users, AdminPasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Credentials credentials = credentials(request.getHeader("Authorization"));
        if (credentials == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin requeridas");
            return false;
        }

        var user = users.findByUsernameIgnoreCase(credentials.username()).orElse(null);
        if (user == null || !user.isActive() || !user.getPasswordHash().equals(passwords.hash(credentials.password()))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin invalidas");
            return false;
        }

        Set<String> permissions = users.permissionCodes(user.getUsername());
        if (permissions.contains(requiredPermission(request).name())) {
            request.setAttribute(AdminAuditService.USERNAME_ATTRIBUTE, user.getUsername());
            return true;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Permiso admin insuficiente");
        return false;
    }

    private AdminPermission requiredPermission(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equals(method) && "/api/v1/admin/companies".equals(path)) {
            return AdminPermission.ADD_COMPANY;
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/admin/companies/")) {
            return AdminPermission.EDIT_COMPANY_DATA;
        }
        if ("POST".equals(method) && path.endsWith("/renew")) {
            return AdminPermission.RENEW_LICENSE;
        }
        if ("POST".equals(method) && path.endsWith("/block")) {
            return AdminPermission.BLOCK_LICENSE;
        }
        if ("POST".equals(method) && path.endsWith("/unblock")) {
            return AdminPermission.UNBLOCK_LICENSE;
        }
        if ("POST".equals(method) && path.endsWith("/pairing-codes")) {
            return AdminPermission.REGENERATE_PAIRING_CODE;
        }
        return AdminPermission.VIEW_ADMIN_DATA;
    }

    private Credentials credentials(String header) {
        if (header == null || !header.startsWith("Basic ")) {
            return null;
        }
        String value;
        try {
            value = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator < 1) {
            return null;
        }
        return new Credentials(value.substring(0, separator), value.substring(separator + 1));
    }

    private record Credentials(String username, String password) {
    }
}

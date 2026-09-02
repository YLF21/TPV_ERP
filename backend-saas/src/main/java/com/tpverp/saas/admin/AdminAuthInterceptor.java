package com.tpverp.saas.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final SaasAdminUserRepository users;
    private final AdminPasswordHasher passwords;
    private final LoginAttemptLimiter attempts;
    private final SaasSessionTokenStore sessions;
    private final LocalAdminCredentialPolicy localCredentials;
    private final boolean legacyBasicAuthEnabled;

    public AdminAuthInterceptor(
            SaasAdminUserRepository users,
            AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts,
            SaasSessionTokenStore sessions,
            LocalAdminCredentialPolicy localCredentials,
            @Value("${tpv.saas.legacy-basic-auth-enabled:false}") boolean legacyBasicAuthEnabled) {
        this.users = users;
        this.passwords = passwords;
        this.attempts = attempts;
        this.sessions = sessions;
        this.localCredentials = localCredentials;
        this.legacyBasicAuthEnabled = legacyBasicAuthEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        String sessionUsername = sessions.username(
                SaasAuthenticationController.bearer(authorization), "admin").orElse(null);
        BasicCredentials credentials = legacyBasicAuthEnabled ? BasicCredentials.parse(authorization) : null;
        String username = sessionUsername != null ? sessionUsername : credentials == null ? null : credentials.username();
        String password = credentials == null ? null : credentials.password();
        String remoteAddress = SaasAuthenticationController.remoteAddress(request);
        if (username == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin requeridas");
            return false;
        }
        if (sessionUsername == null
                && attempts.blocked("admin-account", username, remoteAddress)) {
            response.setHeader("Retry-After", Long.toString(LoginAttemptLimiter.BLOCK_DURATION.toSeconds()));
            response.sendError(429, "Demasiados intentos de autenticacion");
            return false;
        }

        if (sessionUsername == null && !localCredentials.permits(username, password)) {
            attempts.failure("admin-account", username, remoteAddress);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin invalidas");
            return false;
        }

        var user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null || !user.isActive()
                || (sessionUsername == null && !passwords.matches(password, user.getPasswordHash()))) {
            if (sessionUsername == null) {
                attempts.failure("admin-account", username, remoteAddress);
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin invalidas");
            return false;
        }
        if (sessionUsername == null) {
            attempts.success("admin-account", username, remoteAddress);
        }
        if (user.isMustChangePassword()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cambio de password obligatorio");
            return false;
        }
        if (sessionUsername == null && passwords.needsUpgrade(user.getPasswordHash())) {
            user.changePasswordHash(passwords.hash(password));
            users.save(user);
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
        if (path.startsWith("/api/v1/admin/operational-incidents")
                && "POST".equals(method)) {
            return AdminPermission.MANAGE_OPERATIONAL_INCIDENTS;
        }
        if (path.startsWith("/api/v2/admin/")
                && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return AdminPermission.MANAGE_OPERATIONS;
        }
        if ("POST".equals(method) && "/api/v1/admin/companies".equals(path)) {
            return AdminPermission.ADD_COMPANY;
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/admin/verifactu-activation-policies/")) {
            return AdminPermission.MANAGE_FISCAL_POLICY;
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/admin/companies/")) {
            return AdminPermission.EDIT_COMPANY_DATA;
        }
        if (path.contains("/tickets") && ("POST".equals(method) || "PUT".equals(method))) {
            return AdminPermission.MANAGE_SUPPORT_TICKETS;
        }
        if ((path.contains("/invoices") || path.contains("/reconciliations"))
                && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return AdminPermission.MANAGE_BILLING;
        }
        if ((path.contains("/sales-documents") || path.contains("/inventory-")) && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return AdminPermission.MANAGE_OPERATIONS;
        }
        if (path.contains("/subscriptions") && "POST".equals(method)) {
            return AdminPermission.MANAGE_SUBSCRIPTIONS;
        }
        if (path.contains("/integrations") && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return AdminPermission.MANAGE_INTEGRATIONS;
        }
        if (path.contains("/reports/")) {
            return AdminPermission.VIEW_REPORTS;
        }
        if (path.contains("/tenant-users") && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return AdminPermission.MANAGE_TENANT_USERS;
        }
        if (path.contains("/erp/") && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return AdminPermission.MANAGE_ERP_MASTERS;
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/admin/users/")) {
            return AdminPermission.MANAGE_ADMIN_USERS;
        }
        if (("POST".equals(method) || "DELETE".equals(method)) && path.startsWith("/api/v1/admin/users")) {
            return AdminPermission.MANAGE_ADMIN_USERS;
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
        if ("POST".equals(method)
                && path.startsWith("/api/v1/admin/installations/")
                && path.endsWith("/revoke")) {
            return AdminPermission.REVOKE_INSTALLATION;
        }
        return AdminPermission.VIEW_ADMIN_DATA;
    }

}

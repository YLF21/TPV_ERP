package com.tpverp.saas.tenant;

import com.tpverp.saas.admin.AdminPasswordHasher;
import com.tpverp.saas.admin.BasicCredentials;
import com.tpverp.saas.admin.LoginAttemptLimiter;
import com.tpverp.saas.admin.SaasAuthenticationController;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantAuthInterceptor implements HandlerInterceptor {

    private static final String ACCOUNT_SCOPE = "";
    private final SaasTenantUserRepository users;
    private final AdminPasswordHasher passwords;
    private final LoginAttemptLimiter attempts;
    private final SaasSessionTokenStore sessions;
    private final boolean legacyBasicAuthEnabled;

    public TenantAuthInterceptor(
            SaasTenantUserRepository users,
            AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts,
            SaasSessionTokenStore sessions,
            @Value("${tpv.saas.legacy-basic-auth-enabled:false}") boolean legacyBasicAuthEnabled) {
        this.users = users;
        this.passwords = passwords;
        this.attempts = attempts;
        this.sessions = sessions;
        this.legacyBasicAuthEnabled = legacyBasicAuthEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        String sessionUsername = sessions.username(
                SaasAuthenticationController.bearer(authorization), "tenant").orElse(null);
        BasicCredentials credentials = legacyBasicAuthEnabled ? BasicCredentials.parse(authorization) : null;
        String username = sessionUsername != null ? sessionUsername : credentials == null ? null : credentials.username();
        String password = credentials == null ? null : credentials.password();
        if (username == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales cliente requeridas");
            return false;
        }
        if (sessionUsername == null
                && attempts.blocked("tenant-account", username, ACCOUNT_SCOPE)) {
            response.setHeader("Retry-After", Long.toString(LoginAttemptLimiter.BLOCK_DURATION.toSeconds()));
            response.sendError(429, "Demasiados intentos de autenticacion");
            return false;
        }
        SaasTenantUser user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null || !user.isActive()
                || (sessionUsername == null && !passwords.matches(password, user.getPasswordHash()))) {
            if (sessionUsername == null) {
                attempts.failure("tenant-account", username, ACCOUNT_SCOPE);
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales cliente invalidas");
            return false;
        }
        if (sessionUsername == null) {
            attempts.success("tenant-account", username, ACCOUNT_SCOPE);
        }
        if (user.isMustChangePassword()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cambio de password obligatorio");
            return false;
        }
        if (sessionUsername == null && passwords.needsUpgrade(user.getPasswordHash())) {
            user.changePasswordHash(passwords.hash(password));
            users.save(user);
        }
        TenantRole role;
        try {
            role = TenantRole.parse(user.getRoleName());
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Rol cliente no valido");
            return false;
        }
        if (isErpWrite(request) && !role.canWriteErpMasters()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "El rol cliente no puede modificar maestros ERP");
            return false;
        }
        request.setAttribute(TenantContextHolder.ATTRIBUTE, new TenantContext(
                user.getCompany().getId(),
                user.getUsername(),
                role.name()));
        return true;
    }

    private static boolean isErpWrite(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/v1/tenant/erp/")) {
            return false;
        }
        return switch (request.getMethod()) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

}

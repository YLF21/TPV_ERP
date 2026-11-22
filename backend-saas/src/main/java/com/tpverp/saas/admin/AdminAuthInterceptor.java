package com.tpverp.saas.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String ACCOUNT_SCOPE = "";
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
    @Transactional
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
        if (username == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin requeridas");
            return false;
        }
        if (sessionUsername == null
                && attempts.blocked("admin-account", username, ACCOUNT_SCOPE)) {
            response.setHeader("Retry-After", Long.toString(LoginAttemptLimiter.BLOCK_DURATION.toSeconds()));
            response.sendError(429, "Demasiados intentos de autenticacion");
            return false;
        }

        if (sessionUsername == null && !localCredentials.permits(username, password)) {
            attempts.failure("admin-account", username, ACCOUNT_SCOPE);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin invalidas");
            return false;
        }

        var user = (sessionUsername == null ? users.lockByUsername(username) : users.findByUsernameIgnoreCase(username)).orElse(null);
        if (user == null || !user.isActive()
                || (sessionUsername == null && !passwords.matches(password, user.getPasswordHash()))) {
            if (sessionUsername == null) {
                attempts.failure("admin-account", username, ACCOUNT_SCOPE);
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales admin invalidas");
            return false;
        }
        if (sessionUsername == null) {
            attempts.success("admin-account", username, ACCOUNT_SCOPE);
        }
        if (user.isMustChangePassword()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cambio de password obligatorio");
            return false;
        }
        if (sessionUsername == null && passwords.needsUpgrade(user.getPasswordHash())) {
            user.changePasswordHash(passwords.hash(password));
            users.save(user);
        }

        request.setAttribute(AdminAuditService.USERNAME_ATTRIBUTE, user.getUsername());
        return true;
    }
}

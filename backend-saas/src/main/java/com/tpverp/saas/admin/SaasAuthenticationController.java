package com.tpverp.saas.admin;

import com.tpverp.saas.tenant.SaasTenantUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class SaasAuthenticationController {

    private static final String ACCOUNT_SCOPE = "";

    private final SaasAdminUserRepository admins;
    private final SaasTenantUserRepository tenants;
    private final AdminPasswordHasher passwords;
    private final LoginAttemptLimiter attempts;
    private final SaasSessionTokenStore sessions;
    private final LocalAdminCredentialPolicy localCredentials;
    private final AdminAuditService audit;

    @Autowired
    public SaasAuthenticationController(
            SaasAdminUserRepository admins,
            SaasTenantUserRepository tenants,
            AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts,
            SaasSessionTokenStore sessions,
            AdminAuditService audit,
            LocalAdminCredentialPolicy localCredentials) {
        this.audit = audit;
        this.admins = admins;
        this.tenants = tenants;
        this.passwords = passwords;
        this.attempts = attempts;
        this.sessions = sessions;
        this.localCredentials = localCredentials;
    }

    SaasAuthenticationController(
            SaasAdminUserRepository admins,
            SaasTenantUserRepository tenants,
            AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts,
            SaasSessionTokenStore sessions, AdminAuditService audit) {
        this(admins, tenants, passwords, attempts, sessions, audit,
                new LocalAdminCredentialPolicy(java.util.Set.of("test")));
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    @PostMapping("/admin/login")
    public SaasLoginResponse adminLogin(@Valid @RequestBody SaasLoginRequest request) {
        String username = checkLoginAttempt(request);
        var admin = admins.lockByUsername(username).orElse(null);
        if (admin == null || !admin.isActive() || !passwords.matches(request.password(), admin.getPasswordHash())) {
            throw rejectedCredentials(username);
        }
        upgradeAdminPassword(admin, request.password());
        attempts.success("login-account", username, ACCOUNT_SCOPE);
        audit.logAccountAction(admin.getUsername(), "ADMIN_LOGIN");
        return response("admin", admin.getUsername(), admin.isMustChangePassword());
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    @PostMapping({"/tenant/login", "/login"})
    public SaasLoginResponse tenantLogin(@Valid @RequestBody SaasLoginRequest request) {
        String username = checkLoginAttempt(request);
        var tenant = tenants.findByUsernameIgnoreCase(username).orElse(null);
        if (tenant == null || !tenant.isActive() || !passwords.matches(request.password(), tenant.getPasswordHash())) {
            throw rejectedCredentials(username);
        }
        if (passwords.needsUpgrade(tenant.getPasswordHash())) {
            tenant.changePasswordHash(passwords.hash(request.password()));
            tenants.save(tenant);
        }
        attempts.success("login-account", username, ACCOUNT_SCOPE);
        return response("tenant", tenant.getUsername(), tenant.isMustChangePassword());
    }

    private String checkLoginAttempt(SaasLoginRequest request) {
        String username = request.username().trim();
        if (attempts.blocked("login-account", username, ACCOUNT_SCOPE)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos de autenticacion");
        }
        if (!localCredentials.permits(username, request.password())) {
            throw rejectedCredentials(username);
        }
        return username;
    }

    private ResponseStatusException rejectedCredentials(String username) {
        attempts.failure("login-account", username, ACCOUNT_SCOPE);
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    @PostMapping("/refresh")
    public SaasLoginResponse refresh(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        String token = bearer(authorization);
        var identity = sessions.session(token).orElseThrow(SaasAuthenticationController::invalidSession);
        Optional<Boolean> passwordChangeRequired;
        if ("admin".equals(identity.realm())) {
            passwordChangeRequired = admins.lockByUsername(identity.username()).filter(SaasAdminUser::isActive)
                    .map(SaasAdminUser::isMustChangePassword);
        } else if ("tenant".equals(identity.realm())) {
            passwordChangeRequired = tenants.findByUsernameIgnoreCase(identity.username()).filter(user -> user.isActive())
                    .map(user -> user.isMustChangePassword());
        } else {
            passwordChangeRequired = Optional.empty();
        }
        if (passwordChangeRequired.isEmpty()) {
            sessions.revoke(token);
            throw invalidSession();
        }
        var refreshed = sessions.refresh(token).orElseThrow(SaasAuthenticationController::invalidSession);
        return new SaasLoginResponse(refreshed.username(), refreshed.issued().token(), refreshed.realm(),
                refreshed.issued().expiresAt(), passwordChangeRequired.get());
    }

    @Transactional
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(name = "Authorization", required = false) String authorization) {
        sessions.logout(bearer(authorization), false).filter(actor -> "admin".equals(actor.realm()))
                .ifPresent(actor -> audit.logAccountAction(actor.username(), "ADMIN_LOGOUT"));
    }

    @Transactional
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@RequestHeader(name = "Authorization", required = false) String authorization) {
        String token = bearer(authorization);
        sessions.session(token).filter(identity -> "admin".equals(identity.realm()))
                .ifPresent(identity -> admins.lockByUsername(identity.username()));
        var actor = sessions.logout(token, true)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada"));
        if ("admin".equals(actor.realm())) audit.logAccountAction(actor.username(), "ADMIN_LOGOUT_ALL");
    }

    private static ResponseStatusException invalidSession() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada");
    }

    private void upgradeAdminPassword(SaasAdminUser admin, String password) {
        if (passwords.needsUpgrade(admin.getPasswordHash())) {
            admin.changePasswordHash(passwords.hash(password));
            admins.save(admin);
        }
    }

    private SaasLoginResponse response(String realm, String username, boolean passwordChangeRequired) {
        var issued = sessions.issue(realm, username);
        return new SaasLoginResponse(username, issued.token(), realm, issued.expiresAt(), passwordChangeRequired);
    }

    public static String remoteAddress(HttpServletRequest request) {
        String value = request == null ? null : request.getRemoteAddr();
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : null;
    }
}

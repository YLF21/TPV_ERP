package com.tpverp.saas.admin;

import com.tpverp.saas.tenant.SaasTenantUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

    private final SaasAdminUserRepository admins;
    private final SaasTenantUserRepository tenants;
    private final AdminPasswordHasher passwords;
    private final LoginAttemptLimiter attempts;
    private final SaasSessionTokenStore sessions;
    private final LocalAdminCredentialPolicy localCredentials;

    @Autowired
    public SaasAuthenticationController(
            SaasAdminUserRepository admins,
            SaasTenantUserRepository tenants,
            AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts,
            SaasSessionTokenStore sessions,
            LocalAdminCredentialPolicy localCredentials) {
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
            SaasSessionTokenStore sessions) {
        this(admins, tenants, passwords, attempts, sessions,
                new LocalAdminCredentialPolicy(java.util.Set.of("test")));
    }

    @PostMapping("/login")
    public SaasLoginResponse login(
            @Valid @RequestBody SaasLoginRequest request,
            HttpServletRequest httpRequest) {
        return login(request, remoteAddress(httpRequest));
    }

    SaasLoginResponse login(SaasLoginRequest request) {
        return login(request, "unit-test");
    }

    private SaasLoginResponse login(SaasLoginRequest request, String remoteAddress) {
        String username = request.username().trim();
        if (attempts.blocked("login-account", username, remoteAddress)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos de autenticacion");
        }
        if (!localCredentials.permits(username, request.password())) {
            attempts.failure("login-account", username, remoteAddress);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
        }

        var admin = admins.findByUsernameIgnoreCase(username).orElse(null);
        var tenant = tenants.findByUsernameIgnoreCase(username).orElse(null);
        if (admin != null && tenant != null) {
            attempts.failure("login-account", username, remoteAddress);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
        }
        if (admin != null && admin.isActive() && passwords.matches(request.password(), admin.getPasswordHash())) {
            upgradeAdminPassword(admin, request.password());
            attempts.success("login-account", username, remoteAddress);
            return response("admin", admin.getUsername(), admin.isMustChangePassword());
        }

        if (tenant != null && tenant.isActive() && passwords.matches(request.password(), tenant.getPasswordHash())) {
            if (passwords.needsUpgrade(tenant.getPasswordHash())) {
                tenant.changePasswordHash(passwords.hash(request.password()));
                tenants.save(tenant);
            }
            attempts.success("login-account", username, remoteAddress);
            return response("tenant", tenant.getUsername(), tenant.isMustChangePassword());
        }

        attempts.failure("login-account", username, remoteAddress);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
    }

    @PostMapping("/refresh")
    public SaasLoginResponse refresh(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        var refreshed = sessions.refresh(bearer(authorization))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada"));
        if (!activeUser(refreshed.realm(), refreshed.username())) {
            sessions.revoke(refreshed.issued().token());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada");
        }
        return new SaasLoginResponse(
                refreshed.username(), refreshed.issued().token(), refreshed.realm(), refreshed.issued().expiresAt(),
                passwordChangeRequired(refreshed.realm(), refreshed.username()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(name = "Authorization", required = false) String authorization) {
        sessions.revoke(bearer(authorization));
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@RequestHeader(name = "Authorization", required = false) String authorization) {
        if (!sessions.revokeAllForToken(bearer(authorization))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada");
        }
    }

    private boolean activeUser(String realm, String username) {
        if ("admin".equals(realm)) {
            return admins.findByUsernameIgnoreCase(username).filter(SaasAdminUser::isActive).isPresent();
        }
        if ("tenant".equals(realm)) {
            return tenants.findByUsernameIgnoreCase(username).filter(value -> value.isActive()).isPresent();
        }
        return false;
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

    private boolean passwordChangeRequired(String realm, String username) {
        if ("admin".equals(realm)) {
            return admins.findByUsernameIgnoreCase(username)
                    .map(SaasAdminUser::isMustChangePassword).orElse(false);
        }
        return tenants.findByUsernameIgnoreCase(username)
                .map(value -> value.isMustChangePassword()).orElse(false);
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

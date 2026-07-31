package com.tpverp.saas.admin;

import com.tpverp.saas.tenant.SaasTenantUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    public SaasAuthenticationController(
            SaasAdminUserRepository admins,
            SaasTenantUserRepository tenants,
            AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts,
            SaasSessionTokenStore sessions) {
        this.admins = admins;
        this.tenants = tenants;
        this.passwords = passwords;
        this.attempts = attempts;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    public SaasLoginResponse login(@Valid @RequestBody SaasLoginRequest request, HttpServletRequest servletRequest) {
        String username = request.username().trim();
        String remoteAddress = servletRequest.getRemoteAddr();
        if (attempts.blocked("login", username, remoteAddress)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos de autenticacion");
        }

        var admin = admins.findByUsernameIgnoreCase(username).orElse(null);
        if (admin != null && admin.isActive() && passwords.matches(request.password(), admin.getPasswordHash())) {
            upgradeAdminPassword(admin, request.password());
            attempts.success("login", username, remoteAddress);
            return response("admin", admin.getUsername());
        }

        var tenant = tenants.findByUsernameIgnoreCase(username).orElse(null);
        if (tenant != null && tenant.isActive() && passwords.matches(request.password(), tenant.getPasswordHash())) {
            if (passwords.needsUpgrade(tenant.getPasswordHash())) {
                tenant.changePasswordHash(passwords.hash(request.password()));
                tenants.save(tenant);
            }
            attempts.success("login", username, remoteAddress);
            return response("tenant", tenant.getUsername());
        }

        attempts.failure("login", username, remoteAddress);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(name = "Authorization", required = false) String authorization) {
        sessions.revoke(bearer(authorization));
    }

    private void upgradeAdminPassword(SaasAdminUser admin, String password) {
        if (passwords.needsUpgrade(admin.getPasswordHash())) {
            admin.changePasswordHash(passwords.hash(password));
            admins.save(admin);
        }
    }

    private SaasLoginResponse response(String realm, String username) {
        var issued = sessions.issue(realm, username);
        return new SaasLoginResponse(username, issued.token(), realm, issued.expiresAt());
    }

    public static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : null;
    }
}

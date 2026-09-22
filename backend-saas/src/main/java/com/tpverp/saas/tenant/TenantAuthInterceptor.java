package com.tpverp.saas.tenant;

import com.tpverp.saas.admin.AdminPasswordHasher;
import com.tpverp.saas.admin.BasicCredentials;
import com.tpverp.saas.admin.LoginAttemptLimiter;
import com.tpverp.saas.admin.SaasAuthenticationController;
import com.tpverp.saas.admin.SaasSessionTokenStore;
import com.tpverp.saas.access.TenantAccessService;
import com.tpverp.saas.access.TenantAccessResponse.CompanyAccess;
import com.tpverp.saas.access.TenantCompanyPrivilege;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class TenantAuthInterceptor implements HandlerInterceptor {

    private static final String ACCOUNT_SCOPE = "";
    private final SaasTenantUserRepository users;
    private final AdminPasswordHasher passwords;
    private final LoginAttemptLimiter attempts;
    private final SaasSessionTokenStore sessions;
    private final boolean legacyBasicAuthEnabled;
    private final TenantAccessService access;

    @Autowired
    public TenantAuthInterceptor(
            SaasTenantUserRepository users,
            AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts,
            SaasSessionTokenStore sessions,
            TenantAccessService access,
            @Value("${tpv.saas.legacy-basic-auth-enabled:false}") boolean legacyBasicAuthEnabled) {
        this.users = users;
        this.passwords = passwords;
        this.attempts = attempts;
        this.sessions = sessions;
        this.legacyBasicAuthEnabled = legacyBasicAuthEnabled;
        this.access = access;
    }

    /** Kept for the isolated CORS preflight test; never used for an authenticated request. */
    public TenantAuthInterceptor(SaasTenantUserRepository users, AdminPasswordHasher passwords,
            LoginAttemptLimiter attempts, SaasSessionTokenStore sessions, boolean legacyBasicAuthEnabled) {
        this(users, passwords, attempts, sessions, null, legacyBasicAuthEnabled);
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
        // Re-read explicit access on EVERY request; bearer sessions never contain durable grants.
        var available = access.access(user);
        if (matchedPath(request).equals("/api/v1/tenant/access")) {
            request.setAttribute(TenantContextHolder.ATTRIBUTE,
                    new TenantContext(null, user.getUsername(), user.getRoleName(), Set.of(), Set.of()));
            return true;
        }
        UUID requestedCompany = requestedCompany(request);
        CompanyAccess selected;
        if (requestedCompany == null) {
            if (available.companies().isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cuenta sin accesos concedidos");
            if (available.companies().size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Selecciona una empresa autorizada");
            selected = available.companies().getFirst();
        } else {
            selected = available.companies().stream().filter(item -> item.companyId().equals(requestedCompany)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Empresa no autorizada"));
        }
        TenantRole role = TenantRole.parse(selected.roleName());
        TenantCompanyPrivilege required = requiredPrivilege(request);
        if (required != null && !selected.companyPrivileges().contains(required)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso empresarial no concedido");
        }
        if (isErpWrite(request) && (!role.canWriteErpMasters()
                || !selected.companyPrivileges().contains(TenantCompanyPrivilege.WRITE_MASTERS))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El rol cliente no puede modificar maestros ERP");
        }
        request.setAttribute(TenantContextHolder.ATTRIBUTE, new TenantContext(
                selected.companyId(),
                user.getUsername(),
                role.name(), selected.companyPrivileges(),
                selected.stores().stream().map(item -> item.storeId()).collect(Collectors.toUnmodifiableSet())));
        return true;
    }

    private static UUID requestedCompany(HttpServletRequest request) {
        String header = request.getHeader("X-TPV-Company-Id");
        String query = request.getParameter("companyId");
        try {
            UUID fromHeader = header == null || header.isBlank() ? null : UUID.fromString(header);
            UUID fromQuery = query == null || query.isBlank() ? null : UUID.fromString(query);
            if (fromHeader != null && fromQuery != null && !fromHeader.equals(fromQuery)) {
                throw new IllegalArgumentException();
            }
            return fromHeader != null ? fromHeader : fromQuery;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contexto de empresa invalido");
        }
    }

    private static TenantCompanyPrivilege requiredPrivilege(HttpServletRequest request) {
        String path = matchedPath(request);
        if (path.equals("/api/v1/tenant/me") || path.equals("/api/v1/tenant/dashboard")
                || path.equals("/api/v1/tenant/stores") || path.startsWith("/api/v1/tenant/stores/")
                || path.startsWith("/api/v1/tenant/documents/")) return null;
        if (path.startsWith("/api/v1/tenant/erp/")) return TenantCompanyPrivilege.READ_MASTERS;
        if (path.equals("/api/v1/tenant/invoices")) return TenantCompanyPrivilege.READ_BILLING;
        if (path.startsWith("/api/v1/tenant/tickets")) return TenantCompanyPrivilege.SUPPORT;
        return TenantCompanyPrivilege.READ_COMPANY;
    }

    private static boolean isErpWrite(HttpServletRequest request) {
        if (!matchedPath(request).startsWith("/api/v1/tenant/erp/")) {
            return false;
        }
        return switch (request.getMethod()) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    private static String matchedPath(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // Use Spring's matched route, so an encoded URI cannot select a weaker privilege check.
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }

}

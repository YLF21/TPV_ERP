package com.tpverp.saas.tenant;

import com.tpverp.saas.admin.AdminPasswordHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantAuthInterceptor implements HandlerInterceptor {

    private final SaasTenantUserRepository users;
    private final AdminPasswordHasher passwords;

    public TenantAuthInterceptor(SaasTenantUserRepository users, AdminPasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Credentials credentials = credentials(request.getHeader("Authorization"));
        if (credentials == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales cliente requeridas");
            return false;
        }
        SaasTenantUser user = users.findByUsernameIgnoreCase(credentials.username()).orElse(null);
        if (user == null || !user.isActive() || !user.getPasswordHash().equals(passwords.hash(credentials.password()))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Credenciales cliente invalidas");
            return false;
        }
        request.setAttribute(TenantContextHolder.ATTRIBUTE, new TenantContext(
                user.getCompany().getId(),
                user.getUsername(),
                user.getRoleName()));
        return true;
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

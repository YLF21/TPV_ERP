package com.tpverp.backend.security.api;

import com.tpverp.backend.security.domain.UserAccount;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class TemporaryPasswordFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof UserAccount user
                && user.mustChangePassword()
                && !allowed(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"urn:tpv-erp:error:PASSWORD_CHANGE_REQUIRED","title":"Forbidden",\
                    "status":403,"detail":"message.security.password_change_required",\
                    "code":"PASSWORD_CHANGE_REQUIRED"}""");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowed(String path) {
        return "/api/v1/auth/installation-password".equals(path)
                || "/api/v1/auth/logout".equals(path);
    }
}

package com.tpverp.saas.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminApiKeyInterceptor implements HandlerInterceptor {

    private final String adminKey;

    public AdminApiKeyInterceptor(@Value("${tpv.saas.admin-key}") String adminKey) {
        this.adminKey = adminKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String received = request.getHeader("X-TPV-SaaS-Admin-Key");
        if (adminKey.equals(received)) {
            return true;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Admin key invalida");
        return false;
    }
}

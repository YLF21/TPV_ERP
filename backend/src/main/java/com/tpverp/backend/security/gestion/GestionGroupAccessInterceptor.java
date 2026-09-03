package com.tpverp.backend.security.gestion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

final class GestionGroupAccessInterceptor implements HandlerInterceptor {

    private final GestionGroupAccessService access;

    GestionGroupAccessInterceptor(GestionGroupAccessService access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        var requirement = AnnotatedElementUtils.findMergedAnnotation(
                method.getMethod(), RequireGestionGroup.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    method.getBeanType(), RequireGestionGroup.class);
        }
        if (requirement != null) {
            access.requireUnlocked(
                    requirement.value(), SecurityContextHolder.getContext().getAuthentication());
        }
        return true;
    }
}

package com.tpverp.saas.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

final class TenantContextHolder {

    static final String ATTRIBUTE = "tenant.context";

    private TenantContextHolder() {
    }

    static TenantContext current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            Object value = request.getAttribute(ATTRIBUTE);
            if (value instanceof TenantContext context) {
                return context;
            }
        }
        throw new IllegalStateException("No hay contexto tenant en la peticion");
    }
}

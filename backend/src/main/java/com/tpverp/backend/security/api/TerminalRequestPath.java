package com.tpverp.backend.security.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/** Match the servlet's decoded route, so an encoded letter cannot create a second rate-limit budget. */
final class TerminalRequestPath {
    private TerminalRequestPath() { }
    static String canonical(HttpServletRequest request) {
        if (request.getServletPath() != null && !request.getServletPath().isEmpty()) return request.getServletPath();
        try { return UriUtils.decode(raw(request), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException malformedUri) { return raw(request); }
    }
    static String raw(HttpServletRequest request) {
        var context = request.getContextPath();
        var path = request.getRequestURI();
        return context != null && !context.isEmpty() && path.startsWith(context) ? path.substring(context.length()) : path;
    }
}

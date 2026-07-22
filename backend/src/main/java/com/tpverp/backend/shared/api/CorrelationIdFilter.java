package com.tpverp.backend.shared.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var correlationId = valid(request.getHeader(HEADER))
                ? request.getHeader(HEADER)
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put("traceId", correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    public static String getOrCreate(HttpServletRequest request) {
        if (request != null && request.getAttribute(ATTRIBUTE) instanceof String value && valid(value)) {
            return value;
        }
        var generated = UUID.randomUUID().toString();
        if (request != null) request.setAttribute(ATTRIBUTE, generated);
        return generated;
    }

    private static boolean valid(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }
}

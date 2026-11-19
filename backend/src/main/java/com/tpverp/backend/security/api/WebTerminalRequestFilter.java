package com.tpverp.backend.security.api;

import com.tpverp.backend.terminal.TerminalLinkingException;
import com.tpverp.backend.terminal.WebTerminalPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before authentication/body parsing, including error and rate-limit responses. */
final class WebTerminalRequestFilter extends OncePerRequestFilter {
    private final WebTerminalPolicy policy;
    WebTerminalRequestFilter(WebTerminalPolicy policy) { this.policy = policy; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !TerminalRequestPath.canonical(request).startsWith("/api/v1/web-terminal/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        try {
            policy.require(request);
            if (!TerminalRequestPath.raw(request).equals(TerminalRequestPath.canonical(request)))
                throw new TerminalLinkingException(HttpStatus.FORBIDDEN, "WEB_TERMINAL_REQUEST_FORBIDDEN");
        }
        catch (TerminalLinkingException failure) {
            response.setStatus(failure.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"" + failure.getMessage() + "\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

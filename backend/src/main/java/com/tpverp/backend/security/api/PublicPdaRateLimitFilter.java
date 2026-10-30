package com.tpverp.backend.security.api;
import com.tpverp.backend.terminal.TerminalConnectionAddress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

final class PublicPdaRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_SECONDS = 60;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    PublicPdaRateLimitFilter(Clock clock) { this.clock = clock; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return true;
        var path = request.getRequestURI();
        return !path.equals("/api/v1/terminals/pda/request")
                && !path.equals("/api/v1/terminals/pda/link")
                && !path.equals("/api/v1/pda-observability/events")
                && !path.equals("/api/v1/auth/login");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var now = Instant.now(clock).getEpochSecond();
        var limit = request.getRequestURI().equals("/api/v1/auth/login") ? 30
                : request.getRequestURI().equals("/api/v1/pda-observability/events") ? 120 : 10;
        var address = TerminalConnectionAddress.from(request);
        var key = (address == null ? "unknown" : address.getHostAddress()) + "|" + request.getRequestURI();
        var window = windows.compute(key, (ignored, current) -> current == null || now >= current.startedAt + WINDOW_SECONDS
                ? new Window(now, 1) : new Window(current.startedAt, current.count + 1));
        if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> now >= entry.getValue().startedAt + WINDOW_SECONDS);
        if (window.count > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(Math.max(1, window.startedAt + WINDOW_SECONDS - now)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"PDA_RATE_LIMITED\",\"message\":\"Demasiadas solicitudes. Inténtelo de nuevo más tarde.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Window(long startedAt, int count) {}
}

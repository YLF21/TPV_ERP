package com.tpverp.backend.security.api;
import com.tpverp.backend.terminal.TerminalConnectionAddress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

final class PublicPdaRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_SECONDS = 60;
    private static final Set<String> TERMINAL_POST_PATHS = Set.of(
            "/api/v1/terminal-linking/requests", "/api/v1/terminal-linking/requests/status",
            "/api/v1/terminal-linking/requests/cancel", "/api/v1/terminal-linking/server/adopt",
            "/api/v1/terminal-linking/legacy/adopt", "/api/v1/web-terminal/status",
            "/api/v1/web-terminal/request", "/api/v1/web-terminal/cancel", "/api/v1/web-terminal/login");
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    PublicPdaRateLimitFilter(Clock clock) { this.clock = clock; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = TerminalRequestPath.canonical(request);
        if ("GET".equals(request.getMethod()) && path.equals("/api/v1/terminal-linking/bootstrap")) return false;
        if (!"POST".equals(request.getMethod())) return true;
        return !path.equals("/api/v1/terminals/pda/request")
                && !path.equals("/api/v1/terminals/pda/link")
                && !path.equals("/api/v1/pda-observability/events")
                && !path.equals("/api/v1/auth/login")
                && !TERMINAL_POST_PATHS.contains(path);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var now = Instant.now(clock).getEpochSecond();
        var path = TerminalRequestPath.canonical(request);
        var limit = path.equals("/api/v1/auth/login") || path.equals("/api/v1/web-terminal/login") ? 30
                : path.equals("/api/v1/pda-observability/events") ? 120
                : path.equals("/api/v1/web-terminal/status") || path.equals("/api/v1/terminal-linking/requests/status")
                    || path.equals("/api/v1/terminal-linking/bootstrap") ? 60 : 10;
        var address = TerminalConnectionAddress.from(request);
        // Both login transports share the existing per-address password-attempt budget.
        var budget = switch (path) {
            case "/api/v1/web-terminal/login" -> "/api/v1/auth/login";
            case "/api/v1/web-terminal/request" -> "/api/v1/terminal-linking/requests";
            case "/api/v1/web-terminal/cancel" -> "/api/v1/terminal-linking/requests/cancel";
            case "/api/v1/web-terminal/status" -> "/api/v1/terminal-linking/requests/status";
            default -> path;
        };
        var key = (address == null ? "unknown" : address.getHostAddress()) + "|" + budget;
        var window = windows.compute(key, (ignored, current) -> current == null || now >= current.startedAt + WINDOW_SECONDS
                ? new Window(now, 1) : new Window(current.startedAt, current.count + 1));
        if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> now >= entry.getValue().startedAt + WINDOW_SECONDS);
        if (window.count > limit) {
            response.setHeader("Cache-Control", "no-store");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(Math.max(1, window.startedAt + WINDOW_SECONDS - now)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            var code = path.startsWith("/api/v1/web-terminal/") || path.startsWith("/api/v1/terminal-linking/")
                    ? "TERMINAL_RATE_LIMITED" : "PDA_RATE_LIMITED";
            response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Demasiadas solicitudes. Inténtelo de nuevo más tarde.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Window(long startedAt, int count) {}
}

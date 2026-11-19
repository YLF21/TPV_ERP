package com.tpverp.backend.terminal;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Explicit same-origin boundary for the browser's implicit terminal credential. */
public class WebTerminalPolicy {
    public static final String HEADER = "X-espos-web-terminal";
    private final boolean enabled;
    private final String allowedOrigin;
    private final boolean secureCookie;

    public WebTerminalPolicy(boolean enabled, String allowedOrigin, boolean allowLocalHttp) {
        this.enabled = enabled;
        this.allowedOrigin = allowedOrigin;
        this.secureCookie = enabled ? validateOrigin(allowedOrigin, allowLocalHttp) : true;
    }

    public void require(HttpServletRequest request) {
        if (!enabled) throw failure(HttpStatus.NOT_FOUND, "WEB_TERMINAL_DISABLED");
        var origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() != 1 || !allowedOrigin.equals(origins.getFirst()))
            throw failure(HttpStatus.FORBIDDEN, "WEB_TERMINAL_ORIGIN_FORBIDDEN");
        var markers = Collections.list(request.getHeaders(HEADER));
        if (!"POST".equals(request.getMethod()) || markers.size() != 1 || !"1".equals(markers.getFirst()))
            throw failure(HttpStatus.FORBIDDEN, "WEB_TERMINAL_REQUEST_FORBIDDEN");
        try {
            var type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!"application".equals(type.getType()) || !"json".equals(type.getSubtype()))
                throw failure(HttpStatus.FORBIDDEN, "WEB_TERMINAL_REQUEST_FORBIDDEN");
        } catch (IllegalArgumentException invalidContentType) {
            throw failure(HttpStatus.FORBIDDEN, "WEB_TERMINAL_REQUEST_FORBIDDEN");
        }
    }

    public boolean secureCookie() { return secureCookie; }
    public String cookieName() { return secureCookie ? "__Host-espos-terminal" : "espos-terminal-local"; }

    private static boolean validateOrigin(String value, boolean allowLocalHttp) {
        try {
            var uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getPort() == 0 || uri.getPort() > 65535 || !value.equals(uri.toASCIIString())
                    || !host.equals(host.toLowerCase(Locale.ROOT))
                    || "https".equals(uri.getScheme()) && uri.getPort() == 443
                    || "http".equals(uri.getScheme()) && uri.getPort() == 80
                    || value.endsWith(":")) throw new IllegalArgumentException();
            if ("https".equals(uri.getScheme())) return true;
            if (allowLocalHttp && "http".equals(uri.getScheme())
                    && ("127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host))) return false;
        } catch (IllegalArgumentException ignored) {
            // Do not include configured values in error messages.
        }
        throw new IllegalArgumentException("Web terminal requires one explicit HTTPS origin (or an opted-in literal loopback HTTP origin)");
    }

    private static TerminalLinkingException failure(HttpStatus status, String code) {
        return new TerminalLinkingException(status, code);
    }
}

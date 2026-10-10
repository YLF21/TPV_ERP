package com.tpverp.backend.terminal;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Enumeration;

/** Resolves the informational client address without performing DNS lookups. */
public final class TerminalConnectionAddress {
    private TerminalConnectionAddress() {}

    /**
     * Returns the socket peer address, or a single literal X-Forwarded-For value when the peer is
     * loopback. Invalid or ambiguous input falls back to the peer; an invalid peer yields null.
     */
    public static InetAddress from(HttpServletRequest http) {
        if (http == null) return null;

        InetAddress peer = parseLiteral(http.getRemoteAddr());
        if (peer == null || !peer.isLoopbackAddress()) return peer;

        String forwarded = singleForwardedAddress(http);
        InetAddress forwardedAddress = parseLiteral(forwarded);
        return forwardedAddress != null ? forwardedAddress : peer;
    }

    private static String singleForwardedAddress(HttpServletRequest http) {
        Enumeration<String> values = http.getHeaders("X-Forwarded-For");
        if (values == null || !values.hasMoreElements()) return null;

        String value = values.nextElement();
        if (values.hasMoreElements()) return null;
        return value;
    }

    private static InetAddress parseLiteral(String value) {
        if (value == null) return null;
        String literal = value.trim();
        if (literal.isEmpty() || literal.indexOf(',') >= 0 || literal.indexOf('%') >= 0) return null;
        try {
            return InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException invalidLiteral) {
            return null;
        }
    }
}

package com.tpverp.backend.terminal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** A browser-only proof: never returned in JSON or read by application JavaScript. */
@Component
public class WebTerminalCookie {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration LIFETIME = Duration.ofDays(90);
    private final WebTerminalPolicy policy;

    public WebTerminalCookie(WebTerminalPolicy policy) { this.policy = policy; }

    public Optional<Proof> read(HttpServletRequest request) {
        Proof proof = null;
        if (request.getCookies() == null) return Optional.empty();
        for (var cookie : request.getCookies()) {
            if (!policy.cookieName().equals(cookie.getName())) continue;
            if (proof != null) throw invalid();
            proof = parse(cookie.getValue());
        }
        return Optional.ofNullable(proof);
    }

    public Proof create() {
        var secret = new byte[32];
        RANDOM.nextBytes(secret);
        return new Proof(UUID.randomUUID(), UUID.randomUUID(), Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    public void write(HttpServletResponse response, Proof proof) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(policy.cookieName(),
                        "v1." + proof.requestId() + "." + proof.deviceId() + "." + proof.credential())
                .httpOnly(true).secure(policy.secureCookie()).sameSite("Strict").path("/").maxAge(LIFETIME).build().toString());
    }

    private static Proof parse(String value) {
        if (value == null || value.length() > 128) throw invalid();
        var parts = value.split("\\.", -1);
        if (parts.length != 4 || !"v1".equals(parts[0]) || !parts[3].matches("[A-Za-z0-9_-]{43}")) throw invalid();
        try {
            var requestId = UUID.fromString(parts[1]);
            var deviceId = UUID.fromString(parts[2]);
            var raw = Base64.getUrlDecoder().decode(parts[3]);
            if (!requestId.toString().equals(parts[1]) || !deviceId.toString().equals(parts[2]) || raw.length != 32
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(parts[3])) throw invalid();
            return new Proof(requestId, deviceId, parts[3]);
        } catch (IllegalArgumentException invalidValue) { throw invalid(); }
    }

    private static TerminalLinkingException invalid() {
        return new TerminalLinkingException(HttpStatus.FORBIDDEN, "WEB_TERMINAL_PROOF_INVALID");
    }

    public record Proof(UUID requestId, UUID deviceId, String credential) {
        public TerminalLinkingService.Proof linkingProof() { return new TerminalLinkingService.Proof(requestId, credential); }
        @Override public String toString() { return "BrowserTerminalProof[redacted]"; }
    }
}

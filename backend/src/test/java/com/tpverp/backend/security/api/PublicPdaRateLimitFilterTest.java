package com.tpverp.backend.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicPdaRateLimitFilterTest {
    @Test
    void encodedLettersCannotBypassTheSharedLoginAttemptBudget() throws Exception {
        var filter = new PublicPdaRateLimitFilter(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        var accepted = new AtomicInteger();
        MockHttpServletResponse last = null;
        for (int index = 0; index < 31; index++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/%6cogin");
            request.setRemoteAddr("192.0.2.35");
            request.setServletPath("/api/v1/auth/login");
            last = new MockHttpServletResponse();
            filter.doFilter(request, last, (req, res) -> accepted.incrementAndGet());
        }
        assertThat(accepted).hasValue(30);
        assertThat(last.getStatus()).isEqualTo(429);
    }

    @Test
    void browserAndExplicitCredentialLoginsShareOneAttemptBudget() throws Exception {
        var filter = new PublicPdaRateLimitFilter(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        var accepted = new AtomicInteger();
        MockHttpServletResponse last = null;
        for (int index = 0; index < 31; index++) {
            var request = new MockHttpServletRequest("POST", index % 2 == 0 ? "/api/v1/auth/login" : "/api/v1/web-terminal/login");
            request.setRemoteAddr("192.0.2.30");
            last = new MockHttpServletResponse();
            filter.doFilter(request, last, (req, res) -> accepted.incrementAndGet());
        }
        assertThat(accepted).hasValue(30);
        assertThat(last.getStatus()).isEqualTo(429);
    }

    @Test
    void boundsPublicReservationsAndBootstrapWhileAllowingNormalStatusPolling() throws Exception {
        for (var endpoint : new String[] { "/api/v1/web-terminal/request", "/api/v1/terminal-linking/requests",
                "/api/v1/web-terminal/status", "/api/v1/terminal-linking/requests/status", "/api/v1/terminal-linking/bootstrap" }) {
            var filter = new PublicPdaRateLimitFilter(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
            var accepted = new AtomicInteger();
            var limit = endpoint.endsWith("status") || endpoint.endsWith("bootstrap") ? 60 : 10;
            MockHttpServletResponse last = null;
            for (int index = 0; index <= limit; index++) {
                var request = new MockHttpServletRequest(endpoint.endsWith("bootstrap") ? "GET" : "POST", endpoint);
                request.setRemoteAddr("192.0.2.31");
                last = new MockHttpServletResponse();
                filter.doFilter(request, last, (req, res) -> accepted.incrementAndGet());
            }
            assertThat(accepted).hasValue(limit);
            assertThat(last.getStatus()).isEqualTo(429);
            assertThat(last.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(last.getContentAsString()).contains("TERMINAL_RATE_LIMITED");
        }
    }

    @Test
    void limitsAnonymousPdaPairingRequestsPerAddress() throws Exception {
        var filter = new PublicPdaRateLimitFilter(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        var accepted = new AtomicInteger();
        MockHttpServletResponse last = null;
        for (var index = 0; index < 11; index++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/terminals/pda/link");
            request.setRemoteAddr("192.0.2.10");
            last = new MockHttpServletResponse();
            filter.doFilter(request, last, (req, res) -> accepted.incrementAndGet());
        }
        assertThat(accepted).hasValue(10);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    void boundsAuthenticatedPdaTelemetryWithoutBlockingNormalBatches() throws Exception {
        var filter = new PublicPdaRateLimitFilter(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        var accepted = new AtomicInteger();
        MockHttpServletResponse last = null;
        for (var index = 0; index < 121; index++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/pda-observability/events");
            request.setRemoteAddr("192.0.2.11");
            last = new MockHttpServletResponse();
            filter.doFilter(request, last, (req, res) -> accepted.incrementAndGet());
        }
        assertThat(accepted).hasValue(120);
        assertThat(last.getStatus()).isEqualTo(429);
    }

    @Test
    void trustedLoopbackProxySeparatesForwardedClientAddresses() throws Exception {
        var filter = new PublicPdaRateLimitFilter(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        var accepted = new AtomicInteger();
        for (var index = 0; index < 10; index++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/terminals/pda/link");
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", "192.0.2.20");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> accepted.incrementAndGet());
        }
        var otherClient = new MockHttpServletRequest("POST", "/api/v1/terminals/pda/link");
        otherClient.setRemoteAddr("127.0.0.1");
        otherClient.addHeader("X-Forwarded-For", "192.0.2.21");
        var response = new MockHttpServletResponse();
        filter.doFilter(otherClient, response, (req, res) -> accepted.incrementAndGet());

        assertThat(accepted).hasValue(11);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

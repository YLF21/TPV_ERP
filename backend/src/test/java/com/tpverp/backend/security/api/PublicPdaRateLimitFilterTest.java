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

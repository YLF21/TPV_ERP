package com.tpverp.backend.security.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.terminal.WebTerminalPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebTerminalRequestFilterTest {
    @Test void encodedPrefixOrEndpointCannotSkipTheOriginGuardOrCanonicalRouteBoundary() throws Exception {
        var filter = new WebTerminalRequestFilter(new WebTerminalPolicy(true, "https://venta.example.test", false));
        var count = new AtomicInteger();
        for (var raw : new String[] { "/api/v1/%77eb-terminal/status", "/api/v1/web-terminal/%73tatus" }) {
            var request = request("https://venta.example.test");
            request.setRequestURI(raw);
            request.setServletPath("/api/v1/web-terminal/status");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> count.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(count).hasValue(0);
    }

    @Test void disabledOrUntrustedRequestsNeverReachAuthenticationOrBodyParsingAndAreNotCached() throws Exception {
        var count = new AtomicInteger();
        var disabled = new WebTerminalRequestFilter(new WebTerminalPolicy(false, "", false));
        var response = new MockHttpServletResponse();
        disabled.doFilter(request("https://venta.example.test"), response, (req, res) -> count.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("WEB_TERMINAL_DISABLED");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var enabled = new WebTerminalRequestFilter(new WebTerminalPolicy(true, "https://venta.example.test", false));
        for (var origin : new String[] { "", "https://evil.example.test" }) {
            response = new MockHttpServletResponse();
            enabled.doFilter(request(origin), response, (req, res) -> count.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }
        assertThat(count).hasValue(0);
    }

    @Test void acceptedRequestsKeepCacheProtectionWhenALaterFilterRejectsThem() throws Exception {
        var filter = new WebTerminalRequestFilter(new WebTerminalPolicy(true, "https://venta.example.test", false));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("https://venta.example.test"), response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(429));
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    private static MockHttpServletRequest request(String origin) {
        var request = new MockHttpServletRequest("POST", "/api/v1/web-terminal/status");
        if (!origin.isEmpty()) request.addHeader("Origin", origin);
        request.addHeader(WebTerminalPolicy.HEADER, "1");
        request.setContentType("application/json");
        return request;
    }
}

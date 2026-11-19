package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Browser enrollment must not inherit trust from a proxy header or a similar-looking origin. */
class WebTerminalPolicyTest {
    private static final String ORIGIN = "https://venta.example.test:8443";

    @Test
    void acceptsConfiguredHttpsOriginThroughALocalHttpReverseProxy() {
        var policy = new WebTerminalPolicy(true, ORIGIN, false);
        var request = request(ORIGIN);
        request.setRemoteAddr("127.0.0.1");
        request.setSecure(false);

        assertThatCode(() -> policy.require(request)).doesNotThrowAnyException();
        assertThat(policy.cookieName()).isEqualTo("__Host-espos-terminal");
        assertThat(policy.secureCookie()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://venta.example.test", "http://venta.example.test:8443",
            "https://venta.example.test:9443", "https://venta.example.test.evil:8443",
            "https://evil.example.test:8443", "null", "https://venta.example.test:8443/",
            "https://user@venta.example.test:8443", "https://venta.example.test:8443?x=1",
            "https://venta.example.test:8443#fragment"
    })
    void rejectsUnconfiguredOrNonOriginValues(String origin) {
        var policy = new WebTerminalPolicy(true, ORIGIN, false);
        assertThatThrownBy(() -> policy.require(request(origin))).isInstanceOf(TerminalLinkingException.class);
    }

    @Test
    void rejectsMissingAndAmbiguousOriginEvenWhenForwardedHeadersClaimTheAllowedOrigin() {
        var policy = new WebTerminalPolicy(true, ORIGIN, false);
        var missing = request(null);
        missing.addHeader("X-Forwarded-Host", "venta.example.test:8443");
        missing.addHeader("X-Forwarded-Proto", "https");
        missing.addHeader("Forwarded", "for=192.0.2.1;host=venta.example.test:8443;proto=https");
        assertThatThrownBy(() -> policy.require(missing)).isInstanceOf(TerminalLinkingException.class);

        var multiple = request(ORIGIN);
        multiple.addHeader("Origin", "https://evil.example.test");
        assertThatThrownBy(() -> policy.require(multiple)).isInstanceOf(TerminalLinkingException.class);
    }

    @Test
    void requiresAnExplicitJsonPostWithTheBrowserProtocolHeader() {
        var policy = new WebTerminalPolicy(true, ORIGIN, false);
        var get = request(ORIGIN);
        get.setMethod("GET");
        assertThatThrownBy(() -> policy.require(get)).isInstanceOf(TerminalLinkingException.class);

        for (var contentType : new String[] { "text/plain", "application/x-www-form-urlencoded", "multipart/form-data" }) {
            var simple = request(ORIGIN);
            simple.setContentType(contentType);
            assertThatThrownBy(() -> policy.require(simple)).isInstanceOf(TerminalLinkingException.class);
        }
        var noHeader = request(ORIGIN);
        noHeader.removeHeader("X-espos-web-terminal");
        assertThatThrownBy(() -> policy.require(noHeader)).isInstanceOf(TerminalLinkingException.class);

        var duplicateHeader = request(ORIGIN);
        duplicateHeader.addHeader("X-espos-web-terminal", "1");
        assertThatThrownBy(() -> policy.require(duplicateHeader)).isInstanceOf(TerminalLinkingException.class);

        var json = request(ORIGIN);
        json.setContentType("application/json;charset=UTF-8");
        assertThatCode(() -> policy.require(json)).doesNotThrowAnyException();
    }

    @Test
    void disabledRegistrationCannotBeEnabledByRequestHeaders() {
        var disabled = new WebTerminalPolicy(false, ORIGIN, false);
        var request = request(ORIGIN);
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThatThrownBy(() -> disabled.require(request)).isInstanceOf(TerminalLinkingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://127.0.0.1:5173", "http://[::1]:5173" })
    void permitsExplicitLocalHttpWithoutWeakeningTheProductionCookie(String origin) {
        var local = new WebTerminalPolicy(true, origin, true);
        assertThatCode(() -> local.require(request(origin))).doesNotThrowAnyException();
        assertThat(local.cookieName()).isEqualTo("espos-terminal-local");
        assertThat(local.secureCookie()).isFalse();
        assertThat(new WebTerminalPolicy(true, ORIGIN, true).secureCookie()).isTrue();
        assertThatThrownBy(() -> new WebTerminalPolicy(true, origin, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://venta.example.test", "http://localhost:5173", "http://localhost.evil:5173",
            "http://127.0.0.2:5173", "http://192.168.1.2:5173", "//venta.example.test",
            "https://user:password@venta.example.test", "https://venta.example.test/path",
            "https://venta.example.test?x=1", "https://venta.example.test#fragment"
    })
    void rejectsDangerousConfigurationEvenWithLocalHttpEnabled(String origin) {
        assertThatThrownBy(() -> new WebTerminalPolicy(true, origin, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest request(String origin) {
        var request = new MockHttpServletRequest("POST", "/api/v1/web-terminal/status");
        request.setContentType("application/json");
        request.addHeader("X-espos-web-terminal", "1");
        if (origin != null) request.addHeader("Origin", origin);
        return request;
    }
}

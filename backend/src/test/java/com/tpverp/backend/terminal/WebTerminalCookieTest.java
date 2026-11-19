package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.Cookie;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebTerminalCookieTest {
    private static final String REQUEST = "8b0579fd-6384-4c4c-93fd-2c874ac41561";
    private static final String DEVICE = "e56f985c-0ca5-446f-a25f-35270d6cd5ec";
    private static final String SECRET = "JXJq3Qc22fB5arP3l6PJvA67Zf8Lm9YvQYlLkLrPW5c";
    private static final String VALUE = "v1." + REQUEST + "." + DEVICE + "." + SECRET;
    private final WebTerminalCookie cookies = new WebTerminalCookie(
            new WebTerminalPolicy(true, "https://venta.example.test", false));

    @Test
    void missingOrDifferentCookieNamesDoNotInventAnIdentity() {
        var request = new MockHttpServletRequest();
        assertThat(cookies.read(request)).isEmpty();
        request.setCookies(new Cookie("espos-terminal-local", VALUE), new Cookie("unrelated", "value"));
        assertThat(cookies.read(request)).isEmpty();
    }

    @Test
    void createsFreshCryptographicProofsAndRedactsTheirDiagnosticRepresentation() {
        var first = cookies.create();
        var second = cookies.create();
        assertThat(first.requestId()).isNotEqualTo(second.requestId());
        assertThat(first.deviceId()).isNotEqualTo(second.deviceId());
        assertThat(first.credential()).isNotEqualTo(second.credential()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(first.credential())).hasSize(32);
        assertThat(first.toString()).doesNotContain(first.credential());
        assertThat(first.linkingProof().requestId()).isEqualTo(first.requestId());
        assertThat(first.linkingProof().credential()).isEqualTo(first.credential());
    }

    @Test
    void storesTheProofOnlyInAHostScopedSecureHttpOnlyCookie() {
        var proof = cookies.create();
        var response = new MockHttpServletResponse();
        cookies.write(response, proof);
        var header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith("__Host-espos-terminal=v1.")
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain=");
        var cookieValue = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("__Host-espos-terminal", cookieValue));
        assertThat(cookies.read(request)).contains(proof);
    }

    @Test
    void acceptsOnlyOneExactProductionCookieAndRejectsCookieTossing() {
        var valid = new MockHttpServletRequest();
        valid.setCookies(new Cookie("__Host-espos-terminal", VALUE));
        assertThat(cookies.read(valid)).isPresent();

        var duplicate = new MockHttpServletRequest();
        duplicate.setCookies(new Cookie("__Host-espos-terminal", VALUE), new Cookie("__Host-espos-terminal", VALUE));
        assertThatThrownBy(() -> cookies.read(duplicate)).isInstanceOf(TerminalLinkingException.class);

        var conflicting = new MockHttpServletRequest();
        conflicting.setCookies(new Cookie("__Host-espos-terminal", "invalid"), new Cookie("__Host-espos-terminal", VALUE));
        assertThatThrownBy(() -> cookies.read(conflicting)).isInstanceOf(TerminalLinkingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "undefined", "v2." + REQUEST + "." + DEVICE + "." + SECRET,
            "v1.not-a-uuid." + DEVICE + "." + SECRET,
            "v1." + REQUEST + ".not-a-uuid." + SECRET,
            "v1." + REQUEST + "." + DEVICE + ".short",
            "v1." + REQUEST + "." + DEVICE + "." + SECRET + "=",
            "v1." + REQUEST + "." + DEVICE + "." + SECRET + ".extra",
            "v1.8B0579FD-6384-4C4C-93FD-2C874AC41561." + DEVICE + "." + SECRET
    })
    void malformedProofsFailClosedWithoutIncludingTheValueInTheError(String value) {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("__Host-espos-terminal", value));
        assertThatThrownBy(() -> cookies.read(request)).isInstanceOf(TerminalLinkingException.class)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void oversizedCookieIsRejectedAndLocalRegistrationCannotReadTheProductionCookie() {
        var oversized = new MockHttpServletRequest();
        oversized.setCookies(new Cookie("__Host-espos-terminal", "a".repeat(16_384)));
        assertThatThrownBy(() -> cookies.read(oversized)).isInstanceOf(TerminalLinkingException.class);

        var local = new WebTerminalCookie(new WebTerminalPolicy(true, "http://127.0.0.1:5173", true));
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("__Host-espos-terminal", VALUE));
        assertThat(local.read(request)).isEmpty();
    }
}

package com.tpverp.saas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SaasSecurityHeadersFilterTest {

    @Test
    void protectsApiResponsesAndAddsHstsOnlyOverHttps() throws Exception {
        var filter = new SaasSecurityHeadersFilter();
        var request = new MockHttpServletRequest("GET", "/api/v2/loyalty/member-wallet");
        request.setSecure(true);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(response.getHeader("Strict-Transport-Security")).contains("max-age=31536000");
    }
}

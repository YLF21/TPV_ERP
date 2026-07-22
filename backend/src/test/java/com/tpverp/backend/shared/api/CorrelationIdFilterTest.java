package com.tpverp.backend.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void propagatesAValidClientCorrelationId() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "checkout-12345678");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("checkout-12345678");
        assertThat(request.getAttribute(CorrelationIdFilter.ATTRIBUTE)).isEqualTo("checkout-12345678");
    }

    @Test
    void replacesUnsafeIdsInsteadOfReflectingThem() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "bad id\r\nheader");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");
    }
}

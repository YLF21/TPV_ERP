package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpHeaders;

class ProductExcelImportUploadSizeFilterTest {

    private final ProductExcelImportUploadSizeFilter filter = new ProductExcelImportUploadSizeFilter();

    @Test
    void rejectsOversizedExcelRequestBeforeTheChain() throws Exception {
        var request = request("/api/v1/product-excel-imports/preview", ProductExcelImportUploadSizeFilter.MAX_MULTIPART_BYTES + 1);
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> calls.incrementAndGet());

        assertThat(calls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"code\":\"TRANSPORT_REQUEST_TOO_LARGE\"");
        assertThat(response.getContentAsString()).contains("<= 64 MiB");
    }

    @Test
    void rejectsUnknownLengthOnlyForExcelRequestsAndLocalizesCopy() throws Exception {
        var request = request("/api/v1/product-excel-imports/read", -1);
        request.addHeader("Accept-Language", "en");
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> calls.incrementAndGet());

        assertThat(calls).hasValue(0);
        assertThat(response.getContentAsString()).contains("The multipart body exceeds the transport limit");
    }

    @Test
    void leavesNonExcelRoutesUntouchedEvenWhenLengthIsLarge() throws Exception {
        var request = request("/api/v1/products/images", ProductExcelImportUploadSizeFilter.MAX_MULTIPART_BYTES + 1);
        var response = new MockHttpServletResponse();
        var calls = new AtomicInteger();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> calls.incrementAndGet());

        assertThat(calls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String uri, long contentLength) {
        var request = new MockHttpServletRequest("POST", uri);
        request.setContentType("multipart/form-data; boundary=test");
        request.addHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(contentLength));
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return request;
    }
}

package com.tpverp.backend.excel;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects an oversized Excel multipart before the multipart resolver or a
 * controller can materialise its parts. The functional workbook limit remains
 * enforced by {@link ProductExcelImportReadService}; this is only a transport
 * guard for the larger request envelope (file plus JSON configuration).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class ProductExcelImportUploadSizeFilter extends OncePerRequestFilter {

    /** 64 MiB leaves room for the 10 MiB workbook and the bounded JSON contract. */
    public static final long MAX_MULTIPART_BYTES = 64L * 1024L * 1024L;
    private static final String EXCEL_PREFIX = "/api/v1/product-excel-imports";
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        // Mock servlet requests and a few proxies expose the value only as a
        // header.  Use that header strictly; an absent/malformed value must
        // remain unknown and therefore fail closed for Excel uploads.
        if (contentLength < 0) {
            String header = request.getHeader("Content-Length");
            if (header != null && header.matches("[0-9]+")) {
                try {
                    contentLength = Long.parseLong(header);
                } catch (NumberFormatException ignored) {
                    contentLength = -1;
                }
            }
        }
        boolean excelMultipart = request.getRequestURI() != null
                && request.getRequestURI().startsWith(EXCEL_PREFIX)
                && request.getContentType() != null
                && request.getContentType().toLowerCase(java.util.Locale.ROOT)
                        .startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
        if (!excelMultipart) {
            chain.doFilter(request, response);
            return;
        }
        if (contentLength < 0 || contentLength > MAX_MULTIPART_BYTES) {
            writeTooLarge(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        String locale = request.getHeader("Accept-Language");
        boolean english = locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("en");
        boolean chinese = locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        String message = chinese ? "multipart正文超过传输限制" : english
                ? "The multipart body exceeds the transport limit"
                : "El cuerpo multipart supera el limite de transporte";
        String reason = chinese ? "multipart正文超过允许的传输限制" : english
                ? "The multipart body exceeds the permitted transport limit"
                : "El cuerpo multipart supera el limite de transporte permitido";
        String fix = chinese ? "减小文件或配置" : english ? "Reduce the workbook or configuration" : "Reduce el fichero o la configuracion";
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"TRANSPORT_REQUEST_TOO_LARGE\",\"message\":\"" + json(message)
                + "\",\"attribute\":\"file\",\"receivedValue\":null,\"reason\":\"" + json(reason)
                + "\",\"acceptedValues\":\"<= 64 MiB\",\"recommendedFix\":\"" + json(fix) + "\"}");
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}

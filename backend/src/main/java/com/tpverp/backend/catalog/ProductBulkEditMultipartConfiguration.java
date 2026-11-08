package com.tpverp.backend.catalog;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.regex.Pattern;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProductBulkEditMultipartConfiguration {

    private static final Pattern IMAGE_SYNC_PATH = Pattern.compile(
            "/api/v1/product-bulk-edits/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/images");

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> productBulkEditMultipartParts() {
        return factory -> factory.addContextValves(new ValveBase(true) {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if ("PUT".equals(request.getMethod()) && IMAGE_SYNC_PATH.matcher(path).matches()) {
                    // One manifest part accompanies the domain's maximum number of image files.
                    // Keep Tomcat's existing part limit on every other route and method.
                    int configuredLimit = request.getConnector().getMaxPartCount();
                    if (configuredLimit >= 0 && configuredLimit < ProductBulkEditImageService.MAX_IMAGES + 1) {
                        request.setMaxPartCount(ProductBulkEditImageService.MAX_IMAGES + 1);
                    }
                }
                getNext().invoke(request, response);
            }
        });
    }
}

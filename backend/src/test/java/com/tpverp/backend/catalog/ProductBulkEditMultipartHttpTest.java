package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

/** Exercises Tomcat's actual multipart parser; MockMvc does not enforce its part limit. */
class ProductBulkEditMultipartHttpTest {

    private static final String IMAGE_PATH =
            "/api/v1/product-bulk-edits/00000000-0000-0000-0000-000000000001/images";
    private static final String BOUNDARY = "bulk-image-test-boundary";

    @Test
    void acceptsTheFullImageContractOnlyOnTheImageSyncRoute() throws Exception {
        WebServer server = startServer(true);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var maximum = send(client, server, "PUT", IMAGE_PATH, 500);
            assertThat(maximum.statusCode()).isEqualTo(200);
            assertThat(maximum.body()).isEqualTo("501");

            assertThat(send(client, server, "PUT", IMAGE_PATH, 501).statusCode()).isEqualTo(400);
            assertThat(send(client, server, "POST", IMAGE_PATH, 50).statusCode()).isEqualTo(400);
            assertThat(send(client, server, "PUT", "/another-upload", 50).statusCode()).isEqualTo(400);
            assertThat(send(client, server, "PUT", "/another-upload", 49).statusCode()).isEqualTo(200);
        } finally {
            server.stop();
            server.destroy();
        }
    }

    @Test
    void reproducesTheOldFailureAtFiftyImagesWithoutTheCustomizer() throws Exception {
        WebServer server = startServer(false);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(send(client, server, "PUT", IMAGE_PATH, 49).statusCode()).isEqualTo(200);
            assertThat(send(client, server, "PUT", IMAGE_PATH, 50).statusCode()).isEqualTo(400);
        } finally {
            server.stop();
            server.destroy();
        }
    }

    private static WebServer startServer(boolean customized) throws Exception {
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        if (customized) {
            new ProductBulkEditMultipartConfiguration().productBulkEditMultipartParts().customize(factory);
        }
        WebServer server = factory.getWebServer(context -> {
            var registration = context.addServlet("multipartProbe", new HttpServlet() {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    try {
                        response.getWriter().print(request.getParts().size());
                    } catch (ServletException | IllegalStateException rejected) {
                        response.setStatus(400);
                    }
                }
            });
            registration.addMapping("/*");
            registration.setMultipartConfig(new MultipartConfigElement(
                    "", 10L * 1024 * 1024, 110L * 1024 * 1024, 0));
        });
        server.start();
        return server;
    }

    private static HttpResponse<String> send(
            HttpClient client, WebServer server, String method, String path, int imageCount) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"manifest\"; filename=\"manifest.json\"\r\n")
                .append("Content-Type: application/json\r\n\r\n{}\r\n");
        for (int index = 0; index < imageCount; index++) {
            body.append("--").append(BOUNDARY).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"files\"; filename=\"image-")
                    .append(index).append(".png\"\r\n")
                    .append("Content-Type: image/png\r\n\r\nx\r\n");
        }
        body.append("--").append(BOUNDARY).append("--\r\n");
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

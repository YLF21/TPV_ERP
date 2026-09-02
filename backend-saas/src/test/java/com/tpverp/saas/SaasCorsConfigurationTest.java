package com.tpverp.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tpv.saas.cors.allowed-origins=https://panel.example.com")
@ActiveProfiles("test")
class SaasCorsConfigurationTest {

    @LocalServerPort int port;

    @Test
    void permitePreflightV1YV2SoloParaOrigenConfigurado() throws Exception {
        for (String path : java.util.List.of(
                "/api/v1/license/link",
                "/api/v2/loyalty/member-wallet/reservations")) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://panel.example.com")
                    .header("Access-Control-Request-Method", "POST")
                    .build();

            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .contains("https://panel.example.com");
        }
    }
}

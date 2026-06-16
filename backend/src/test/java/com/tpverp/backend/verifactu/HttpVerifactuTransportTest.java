package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpVerifactuTransportTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void enviaSoapPorPostYDevuelveRespuesta() throws Exception {
        var receivedBody = new AtomicReference<String>();
        var receivedContentType = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verifactu", exchange -> {
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = "<respuesta>ok</respuesta>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        var response = transport().send(
                "http://127.0.0.1:%d/verifactu".formatted(server.getAddress().getPort()),
                "<soap>hola</soap>");

        assertThat(response.httpStatus()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("<respuesta>ok</respuesta>");
        assertThat(receivedBody.get()).isEqualTo("<soap>hola</soap>");
        assertThat(receivedContentType.get()).contains("text/xml");
    }

    @Test
    void convierteErroresDeRedEnExcepcionDeTransporte() {
        assertThatThrownBy(() -> transport().send(
                "http://127.0.0.1:1/no-disponible", "<soap/>"))
                .isInstanceOf(VerifactuTransportException.class)
                .hasMessageContaining("envio VERI*FACTU");
    }

    private static HttpVerifactuTransport transport() {
        return new HttpVerifactuTransport(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build());
    }
}

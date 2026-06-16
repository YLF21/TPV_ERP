package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class HttpVerifactuTransport implements VerifactuTransport {

    private final HttpClient client;

    public HttpVerifactuTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public VerifactuTransportResponse send(String endpoint, String soapEnvelope) {
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "text/xml; charset=UTF-8")
                    .header("SOAPAction", "")
                    .POST(HttpRequest.BodyPublishers.ofString(soapEnvelope, StandardCharsets.UTF_8))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new VerifactuTransportResponse(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new VerifactuTransportException("Error de envio VERI*FACTU", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VerifactuTransportException("Error de envio VERI*FACTU", exception);
        } catch (IllegalArgumentException exception) {
            throw new VerifactuTransportException("Error de envio VERI*FACTU", exception);
        }
    }
    // Envia el SOAP a AEAT o al endpoint configurado usando el HttpClient preparado con mTLS.
}

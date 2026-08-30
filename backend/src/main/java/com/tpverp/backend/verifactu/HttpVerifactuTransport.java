package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class HttpVerifactuTransport implements VerifactuTransport {

    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
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
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(soapEnvelope, StandardCharsets.UTF_8))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            var declaredLength = response.headers().firstValueAsLong("Content-Length");
            if (declaredLength.isPresent() && declaredLength.getAsLong() > MAX_RESPONSE_BYTES) {
                response.body().close();
                throw new VerifactuTransportException(
                        "La respuesta VERI*FACTU supera el limite permitido");
            }
            try (var body = response.body()) {
                var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new VerifactuTransportException(
                            "La respuesta VERI*FACTU supera el limite permitido");
                }
                return new VerifactuTransportResponse(
                        response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            throw new VerifactuTransportException("Error de envio VERI*FACTU", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VerifactuTransportException("Error de envio VERI*FACTU", exception);
        } catch (IllegalArgumentException exception) {
            throw new VerifactuTransportException("Error de envio VERI*FACTU", exception);
        }
    }

    @Override
    public VerifactuTransportResponse send(
            UUID companyId, UUID installationId, String endpoint, String soapEnvelope) {
        if (companyId == null || installationId == null) {
            throw new IllegalArgumentException("scope fiscal obligatorio");
        }
        return send(endpoint, soapEnvelope);
    }
    // Envia el SOAP a AEAT o al endpoint configurado usando el HttpClient preparado con mTLS.
}

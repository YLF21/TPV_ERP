package com.tpverp.backend.verifactu;

import java.util.concurrent.atomic.AtomicReference;

/** Deterministic, in-memory transport used only by the fiscal DEV sandbox. */
public class SimulatedAeatTransport implements VerifactuTransport {

    private static final String ACCEPTED = """
            <RespuestaRegFactuSistemaFacturacion>
              <EstadoEnvio>Correcto</EstadoEnvio>
            </RespuestaRegFactuSistemaFacturacion>
            """;
    private static final String ACCEPTED_WITH_ERRORS = """
            <RespuestaRegFactuSistemaFacturacion>
              <EstadoEnvio>ParcialmenteCorrecto</EstadoEnvio>
              <CodigoErrorRegistro>DEV-2001</CodigoErrorRegistro>
              <DescripcionErrorRegistro>Respuesta simulada con errores</DescripcionErrorRegistro>
            </RespuestaRegFactuSistemaFacturacion>
            """;
    private static final String REJECTED = """
            <RespuestaRegFactuSistemaFacturacion>
              <EstadoEnvio>Incorrecto</EstadoEnvio>
              <CodigoErrorRegistro>DEV-3000</CodigoErrorRegistro>
              <DescripcionErrorRegistro>Respuesta simulada rechazada</DescripcionErrorRegistro>
            </RespuestaRegFactuSistemaFacturacion>
            """;
    private static final String DUPLICATE = """
            <RespuestaRegFactuSistemaFacturacion>
              <EstadoEnvio>Incorrecto</EstadoEnvio>
              <CodigoErrorRegistro>3000</CodigoErrorRegistro>
              <DescripcionErrorRegistro>Registro duplicado simulado</DescripcionErrorRegistro>
              <IdPeticionRegistroDuplicado>DEV-DUPLICATE</IdPeticionRegistroDuplicado>
              <EstadoRegistroDuplicado>Correcta</EstadoRegistroDuplicado>
            </RespuestaRegFactuSistemaFacturacion>
            """;

    private final AtomicReference<SimulatedAeatOutcome> next =
            new AtomicReference<>(SimulatedAeatOutcome.ACCEPTED);

    @Override
    public VerifactuTransportResponse send(String endpoint, String soapEnvelope) {
        var outcome = next.getAndSet(SimulatedAeatOutcome.ACCEPTED);
        return switch (outcome) {
            case ACCEPTED -> new VerifactuTransportResponse(200, ACCEPTED);
            case ACCEPTED_WITH_ERRORS -> new VerifactuTransportResponse(200, ACCEPTED_WITH_ERRORS);
            case REJECTED -> new VerifactuTransportResponse(200, REJECTED);
            case DUPLICATE -> new VerifactuTransportResponse(200, DUPLICATE);
            case HTTP_ERROR -> new VerifactuTransportResponse(503, "simulated HTTP error");
            case INVALID_RESPONSE -> new VerifactuTransportResponse(200, "not xml");
            case TIMEOUT -> throw new VerifactuTransportException("Timeout simulado AEAT");
        };
    }

    public void setNextOutcome(SimulatedAeatOutcome outcome) {
        next.set(outcome == null ? SimulatedAeatOutcome.ACCEPTED : outcome);
    }

    public SimulatedAeatOutcome nextOutcome() {
        return next.get();
    }
}

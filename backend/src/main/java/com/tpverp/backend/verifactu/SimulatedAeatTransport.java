package com.tpverp.backend.verifactu;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

/** Deterministic, in-memory transport used only by the fiscal DEV sandbox. */
public class SimulatedAeatTransport implements VerifactuTransport {

    private static final String RESPONSE_NS = VerifactuResponseParser.RESPONSE_NAMESPACE;
    private static final String SUPPLY_NS = VerifactuResponseParser.SUPPLY_NAMESPACE;

    private static final String ACCEPTED = """
            <sfR:RespuestaRegFactuSistemaFacturacion xmlns:sfR="%s" xmlns:sf="%s">
              <sfR:Cabecera><sf:ObligadoEmision><sf:NombreRazon>TPV ERP</sf:NombreRazon><sf:NIF>B00000000</sf:NIF></sf:ObligadoEmision></sfR:Cabecera>
              <sfR:TiempoEsperaEnvio>60</sfR:TiempoEsperaEnvio><sfR:EstadoEnvio>Correcto</sfR:EstadoEnvio>
            </sfR:RespuestaRegFactuSistemaFacturacion>
            """.formatted(RESPONSE_NS, SUPPLY_NS);
    private static final String ACCEPTED_WITH_ERRORS = """
            <sfR:RespuestaRegFactuSistemaFacturacion xmlns:sfR="%s" xmlns:sf="%s">
              <sfR:Cabecera><sf:ObligadoEmision><sf:NombreRazon>TPV ERP</sf:NombreRazon><sf:NIF>B00000000</sf:NIF></sf:ObligadoEmision></sfR:Cabecera>
              <sfR:TiempoEsperaEnvio>60</sfR:TiempoEsperaEnvio><sfR:EstadoEnvio>ParcialmenteCorrecto</sfR:EstadoEnvio>
            </sfR:RespuestaRegFactuSistemaFacturacion>
            """.formatted(RESPONSE_NS, SUPPLY_NS);
    private static final String REJECTED = """
            <sfR:RespuestaRegFactuSistemaFacturacion xmlns:sfR="%s" xmlns:sf="%s">
              <sfR:Cabecera><sf:ObligadoEmision><sf:NombreRazon>TPV ERP</sf:NombreRazon><sf:NIF>B00000000</sf:NIF></sf:ObligadoEmision></sfR:Cabecera>
              <sfR:TiempoEsperaEnvio>60</sfR:TiempoEsperaEnvio><sfR:EstadoEnvio>Incorrecto</sfR:EstadoEnvio>
            </sfR:RespuestaRegFactuSistemaFacturacion>
            """.formatted(RESPONSE_NS, SUPPLY_NS);
    private static final String DUPLICATE = """
            <sfR:RespuestaRegFactuSistemaFacturacion xmlns:sfR="%s" xmlns:sf="%s">
              <sfR:Cabecera><sf:ObligadoEmision><sf:NombreRazon>TPV ERP</sf:NombreRazon><sf:NIF>B00000000</sf:NIF></sf:ObligadoEmision></sfR:Cabecera>
              <sfR:TiempoEsperaEnvio>60</sfR:TiempoEsperaEnvio><sfR:EstadoEnvio>Incorrecto</sfR:EstadoEnvio>
            </sfR:RespuestaRegFactuSistemaFacturacion>
            """.formatted(RESPONSE_NS, SUPPLY_NS);

    private final AtomicReference<SimulatedAeatOutcome> next =
            new AtomicReference<>(SimulatedAeatOutcome.ACCEPTED);

    @Override
    public VerifactuTransportResponse send(String endpoint, String soapEnvelope) {
        var outcome = next.getAndSet(SimulatedAeatOutcome.ACCEPTED);
        return switch (outcome) {
            case ACCEPTED -> new VerifactuTransportResponse(200, structural(soapEnvelope, "Correcto", "Correcto"));
            case ACCEPTED_WITH_ERRORS -> new VerifactuTransportResponse(200,
                    structural(soapEnvelope, "ParcialmenteCorrecto", "AceptadoConErrores"));
            case REJECTED -> new VerifactuTransportResponse(200,
                    structural(soapEnvelope, "Incorrecto", "Incorrecto"));
            case DUPLICATE -> new VerifactuTransportResponse(200, duplicateStructural(soapEnvelope));
            case HTTP_ERROR -> new VerifactuTransportResponse(503, "simulated HTTP error");
            case INVALID_RESPONSE -> new VerifactuTransportResponse(200, "not xml");
            case TIMEOUT -> throw new VerifactuTransportException("Timeout simulado AEAT");
        };
    }

    @Override
    public VerifactuTransportResponse send(
            UUID companyId, UUID installationId, String endpoint, String soapEnvelope) {
        if (companyId == null || installationId == null) {
            throw new IllegalArgumentException("scope fiscal obligatorio");
        }
        return send(endpoint, soapEnvelope);
    }

    private static String structural(String envelope, String global, String lineStatus) {
        var lines = new StringBuilder();
        var pattern = Pattern.compile("<(?:(?:\\w+):)?Registro(Alta|Anulacion)\\b.*?</(?:(?:\\w+):)?Registro\\1>",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(envelope == null ? "" : envelope);
        while (matcher.find()) {
            var node = matcher.group();
            var issuer = value(node, "IDEmisorFactura(?:Anulada)?");
            var number = value(node, "NumSerieFactura(?:Anulada)?");
            var date = value(node, "FechaExpedicionFactura(?:Anulada)?");
            lines.append("<sfR:RespuestaLinea><sfR:IDFactura><sf:IDEmisorFactura>")
                    .append(issuer).append("</sf:IDEmisorFactura><sf:NumSerieFactura>")
                    .append(number).append("</sf:NumSerieFactura><sf:FechaExpedicionFactura>")
                    .append(date).append("</sf:FechaExpedicionFactura></sfR:IDFactura><sfR:Operacion><sf:TipoOperacion>")
                    .append("Alta".equals(matcher.group(1)) ? "Alta" : "Anulacion")
                    .append("</sf:TipoOperacion></sfR:Operacion><sfR:EstadoRegistro>").append(lineStatus)
                    .append("</sfR:EstadoRegistro>");
            if ("AceptadoConErrores".equals(lineStatus)) {
                lines.append("<sfR:CodigoErrorRegistro>2001</sfR:CodigoErrorRegistro>")
                        .append("<sfR:DescripcionErrorRegistro>Respuesta simulada con errores</sfR:DescripcionErrorRegistro>");
            } else if ("Incorrecto".equals(lineStatus)) {
                lines.append("<sfR:CodigoErrorRegistro>3000</sfR:CodigoErrorRegistro>")
                        .append("<sfR:DescripcionErrorRegistro>Respuesta simulada rechazada</sfR:DescripcionErrorRegistro>");
            }
            lines.append("</sfR:RespuestaLinea>");
        }
        return "<sfR:RespuestaRegFactuSistemaFacturacion xmlns:sfR=\"" + RESPONSE_NS
                + "\" xmlns:sf=\"" + SUPPLY_NS + "\"><sfR:Cabecera><sf:ObligadoEmision>"
                + "<sf:NombreRazon>TPV ERP</sf:NombreRazon><sf:NIF>B00000000</sf:NIF>"
                + "</sf:ObligadoEmision></sfR:Cabecera><sfR:TiempoEsperaEnvio>60</sfR:TiempoEsperaEnvio><sfR:EstadoEnvio>" + global
                + "</sfR:EstadoEnvio>"
                + lines + "</sfR:RespuestaRegFactuSistemaFacturacion>";
    }

    private static String duplicateStructural(String envelope) {
        var response = structural(envelope, "Incorrecto", "Incorrecto");
        return response.replaceFirst("</sfR:DescripcionErrorRegistro>",
                "</sfR:DescripcionErrorRegistro><sfR:RegistroDuplicado>"
                        + "<sf:IdPeticionRegistroDuplicado>DEV-DUPLICATE</sf:IdPeticionRegistroDuplicado>"
                        + "<sf:EstadoRegistroDuplicado>Correcta</sf:EstadoRegistroDuplicado>"
                        + "</sfR:RegistroDuplicado>");
    }

    private static String value(String xml, String localName) {
        var matcher = Pattern.compile("<(?:\\w+:)?" + localName + ">([^<]*)</(?:\\w+:)?"
                + localName + ">", Pattern.DOTALL).matcher(xml);
        return matcher.find() ? matcher.group(1) : "";
    }

    public void setNextOutcome(SimulatedAeatOutcome outcome) {
        next.set(outcome == null ? SimulatedAeatOutcome.ACCEPTED : outcome);
    }

    public SimulatedAeatOutcome nextOutcome() {
        return next.get();
    }
}

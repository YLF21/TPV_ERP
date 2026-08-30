package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerifactuBatchResponseParserTest {
    private final VerifactuResponseParser parser = new VerifactuResponseParser();

    @Test
    void exigeTodasLasLineasYCorrelacionaPorOperacionEIdentidad() {
        var record = record(FiscalRecordOperation.ALTA, "F-1");
        var response = parser.parseBatch(new VerifactuTransportResponse(200, response(
                "Correcto", "Correcto", "60", record)), List.of(record));

        assertThat(response.validFor(List.of(record))).isTrue();
        assertThat(response.waitSeconds()).isEqualTo(60);
        assertThat(response.lines()).containsKey(record.getId());
    }

    @Test
    void faltaTiempoEsperaEsRespuestaInvalida() {
        var record = record(FiscalRecordOperation.ALTA, "F-1");
        var result = parser.parseBatch(new VerifactuTransportResponse(200,
                response("Correcto", "Correcto", null, record)), List.of(record));

        assertThat(result.errorCode()).isEqualTo("INVALID_AEAT_RESPONSE");
        assertThat(result.globalStatus()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
    }

    @Test
    void lineaDesconocidaNoSeConvierteEnRechazoAeat() {
        var record = record(FiscalRecordOperation.ALTA, "F-1");
        var unknown = record(FiscalRecordOperation.ALTA, "F-OTHER");
        var result = parser.parseBatch(new VerifactuTransportResponse(200,
                response("Correcto", "Correcto", "60", unknown)), List.of(record));

        assertThat(result.globalStatus()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
        assertThat(result.errorCode()).isEqualTo("INVALID_AEAT_RESPONSE");
    }

    @Test
    void parcialmenteCorrectoPermiteCorrectoAceptadoConErroresEIncorrecto() {
        var one = record(FiscalRecordOperation.ALTA, "F-1");
        var two = record(FiscalRecordOperation.ALTA, "F-2");
        var three = record(FiscalRecordOperation.ALTA, "F-3");
        var payload = responseWithLines("ParcialmenteCorrecto",
                line(one, "Correcto", null),
                line(two, "AceptadoConErrores", "2001"),
                line(three, "Incorrecto", "3000"));
        var result = parser.parseBatch(new VerifactuTransportResponse(200, payload),
                List.of(one, two, three));
        assertThat(result.validFor(List.of(one, two, three))).isTrue();
    }

    @Test
    void espera86400EsInvalidaSegunTipo6() {
        var record = record(FiscalRecordOperation.ALTA, "F-1");
        var result = parser.parseBatch(new VerifactuTransportResponse(200,
                response("Correcto", "Correcto", "86400", record)), List.of(record));
        assertThat(result.globalStatus()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
    }

    @Test
    void duplicadoCorrectaCierraLocalmenteComoAceptadoManteniendoEstadoBruto() {
        var record = record(FiscalRecordOperation.ALTA, "F-1");
        var result = parser.parseBatch(new VerifactuTransportResponse(200,
                responseWithLines("Incorrecto", duplicateLine(record, "Correcta", null))),
                List.of(record));

        var line = result.lines().get(record.getId());
        assertThat(line.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        assertThat(line.rawStatus()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(line.duplicateStatus()).isEqualTo("Correcta");
    }

    @Test
    void duplicadoAceptadaConErroresConservaDetalle() {
        var record = record(FiscalRecordOperation.ALTA, "F-1");
        var result = parser.parseBatch(new VerifactuTransportResponse(200,
                responseWithLines("Incorrecto", duplicateLine(record, "AceptadaConErrores", "3100"))),
                List.of(record));

        var line = result.lines().get(record.getId());
        assertThat(line.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);
        assertThat(line.rawStatus()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(line.errorCode()).isEqualTo("3000");
        assertThat(line.error()).contains("detalle duplicado").contains("3100");
    }

    @Test
    void duplicadoAnuladaNoSeConsideraAceptado() {
        var record = record(FiscalRecordOperation.ALTA, "F-1");
        var result = parser.parseBatch(new VerifactuTransportResponse(200,
                responseWithLines("Incorrecto", duplicateLine(record, "Anulada", "3200"))),
                List.of(record));

        assertThat(result.lines().get(record.getId()).status())
                .isEqualTo(FiscalSubmissionStatus.RECHAZADO);
    }

    private static String response(String global, String lineStatus, String wait,
            FiscalRecord record) {
        var ns = "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/";
        return "<sfR:RespuestaRegFactuSistemaFacturacion xmlns:sfR=\"" + ns
                + "RespuestaSuministro.xsd\" xmlns:sf=\"" + ns
                + "SuministroInformacion.xsd\"><sfR:Cabecera><sf:ObligadoEmision>"
                + "<sf:NombreRazon>TPV</sf:NombreRazon><sf:NIF>B00000000</sf:NIF>"
                + "</sf:ObligadoEmision></sfR:Cabecera>"
                + (wait == null ? "" : "<sfR:TiempoEsperaEnvio>" + wait
                        + "</sfR:TiempoEsperaEnvio>") + "<sfR:EstadoEnvio>" + global
                + "</sfR:EstadoEnvio><sfR:RespuestaLinea><sfR:IDFactura>"
                + "<sf:IDEmisorFactura>B12345674</sf:IDEmisorFactura><sf:NumSerieFactura>"
                + record.getNumber() + "</sf:NumSerieFactura><sf:FechaExpedicionFactura>"
                + "16-06-2026</sf:FechaExpedicionFactura></sfR:IDFactura><sfR:Operacion>"
                + "<sf:TipoOperacion>Alta</sf:TipoOperacion></sfR:Operacion><sfR:EstadoRegistro>"
                + lineStatus + "</sfR:EstadoRegistro></sfR:RespuestaLinea>"
                + "</sfR:RespuestaRegFactuSistemaFacturacion>";
    }

    private static String responseWithLines(String global, String... lines) {
        var ns = "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/";
        return "<sfR:RespuestaRegFactuSistemaFacturacion xmlns:sfR=\"" + ns
                + "RespuestaSuministro.xsd\" xmlns:sf=\"" + ns
                + "SuministroInformacion.xsd\"><sfR:Cabecera><sf:ObligadoEmision>"
                + "<sf:NombreRazon>TPV</sf:NombreRazon><sf:NIF>B00000000</sf:NIF>"
                + "</sf:ObligadoEmision></sfR:Cabecera><sfR:TiempoEsperaEnvio>60</sfR:TiempoEsperaEnvio>"
                + "<sfR:EstadoEnvio>" + global + "</sfR:EstadoEnvio>"
                + String.join("", lines)
                + "</sfR:RespuestaRegFactuSistemaFacturacion>";
    }

    private static String line(FiscalRecord record, String status, String code) {
        var ns = "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/";
        return "<sfR:RespuestaLinea xmlns:sfR=\"" + ns
                + "RespuestaSuministro.xsd\" xmlns:sf=\"" + ns
                + "SuministroInformacion.xsd\"><sfR:IDFactura><sf:IDEmisorFactura>B12345674</sf:IDEmisorFactura>"
                + "<sf:NumSerieFactura>" + record.getNumber() + "</sf:NumSerieFactura>"
                + "<sf:FechaExpedicionFactura>16-06-2026</sf:FechaExpedicionFactura></sfR:IDFactura>"
                + "<sfR:Operacion><sf:TipoOperacion>Alta</sf:TipoOperacion></sfR:Operacion>"
                + "<sfR:EstadoRegistro>" + status + "</sfR:EstadoRegistro>"
                + (code == null ? "" : "<sfR:CodigoErrorRegistro>" + code
                        + "</sfR:CodigoErrorRegistro><sfR:DescripcionErrorRegistro>error</sfR:DescripcionErrorRegistro>")
                + "</sfR:RespuestaLinea>";
    }

    private static String duplicateLine(FiscalRecord record, String duplicateStatus, String code) {
        var ns = "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/";
        return "<sfR:RespuestaLinea xmlns:sfR=\"" + ns
                + "RespuestaSuministro.xsd\" xmlns:sf=\"" + ns + "SuministroInformacion.xsd\">"
                + "<sfR:IDFactura><sf:IDEmisorFactura>B12345674</sf:IDEmisorFactura>"
                + "<sf:NumSerieFactura>" + record.getNumber() + "</sf:NumSerieFactura>"
                + "<sf:FechaExpedicionFactura>16-06-2026</sf:FechaExpedicionFactura></sfR:IDFactura>"
                + "<sfR:Operacion><sf:TipoOperacion>Alta</sf:TipoOperacion></sfR:Operacion>"
                + "<sfR:EstadoRegistro>Incorrecto</sfR:EstadoRegistro>"
                + "<sfR:CodigoErrorRegistro>3000</sfR:CodigoErrorRegistro>"
                + "<sfR:DescripcionErrorRegistro>Registro duplicado</sfR:DescripcionErrorRegistro>"
                + "<sfR:RegistroDuplicado><sf:IdPeticionRegistroDuplicado>ABC123</sf:IdPeticionRegistroDuplicado>"
                + "<sf:EstadoRegistroDuplicado>" + duplicateStatus + "</sf:EstadoRegistroDuplicado>"
                + (code == null ? "" : "<sf:CodigoErrorRegistro>" + code + "</sf:CodigoErrorRegistro>"
                        + "<sf:DescripcionErrorRegistro>detalle duplicado</sf:DescripcionErrorRegistro>")
                + "</sfR:RegistroDuplicado></sfR:RespuestaLinea>";
    }

    private static FiscalRecord record(FiscalRecordOperation operation, String number) {
        var result = org.mockito.Mockito.mock(FiscalRecord.class);
        when(result.getId()).thenReturn(UUID.randomUUID());
        when(result.getIssuerTaxId()).thenReturn("B12345674");
        when(result.getNumber()).thenReturn(number);
        when(result.getIssueDate()).thenReturn(LocalDate.of(2026, 6, 16));
        when(result.getOperation()).thenReturn(operation);
        return result;
    }
}

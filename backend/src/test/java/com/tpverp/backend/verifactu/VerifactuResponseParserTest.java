package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerifactuResponseParserTest {

    @Test
    void clasificaAceptadoConErrores() {
        var result = parser().parse(new VerifactuTransportResponse(200, """
                <RespuestaRegFactuSistemaFacturacion>
                  <EstadoEnvio>ParcialmenteCorrecto</EstadoEnvio>
                  <CodigoErrorRegistro>2001</CodigoErrorRegistro>
                  <DescripcionErrorRegistro>Campo opcional invalido</DescripcionErrorRegistro>
                </RespuestaRegFactuSistemaFacturacion>
                """));

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);
        assertThat(result.errorCode()).isEqualTo("2001");
        assertThat(result.error()).isEqualTo("Campo opcional invalido");
    }

    @Test
    void mantieneReintentoAnteHttpNoAceptado() {
        var result = parser().parse(new VerifactuTransportResponse(503, "servicio no disponible"));

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ENVIADO);
        assertThat(result.errorCode()).isEqualTo("HTTP_503");
    }

    @Test
    void marcaDefectuosoSiLaRespuestaNoEsXml() {
        var result = parser().parse(new VerifactuTransportResponse(200, "no es xml"));

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
        assertThat(result.errorCode()).isEqualTo("INVALID_AEAT_RESPONSE");
    }

    private static VerifactuResponseParser parser() {
        return new VerifactuResponseParser();
    }
}

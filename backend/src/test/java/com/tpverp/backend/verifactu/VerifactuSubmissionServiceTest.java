package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerifactuSubmissionServiceTest {

    @Mock private VerifactuXmlService xml;
    @Mock private VerifactuSoapEnvelopeService soap;
    @Mock private VerifactuEndpointResolver endpoints;
    @Mock private VerifactuSubmissionPropertiesFactory properties;
    @Mock private VerifactuTransport transport;
    @Mock private FiscalSubmissionAttemptService attempts;
    @Mock private VerifactuOfficialXsdValidator validator;

    private FiscalRecord record;
    private VerifactuSubmissionService service;

    @BeforeEach
    void setUp() {
        record = record();
        when(properties.current()).thenReturn(new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, java.nio.file.Path.of("cert.p12"),
                "secret".toCharArray(), "TPV ERP", "01"));
        lenient().when(endpoints.resolve(VerifactuEndpointMode.TEST))
                .thenReturn("https://aeat.test/soap");
        when(xml.batchXml(any())).thenReturn("<sfLR:RegFactuSistemaFacturacion/>");
        lenient().when(soap.wrap("<sfLR:RegFactuSistemaFacturacion/>"))
                .thenReturn("<soap/>");
        service = new VerifactuSubmissionService(
                xml, soap, endpoints, properties, transport, attempts,
                new VerifactuResponseParser(), validator);
    }

    @Test
    void enviaElRegistroYMarcaAceptado() {
        when(transport.send("https://aeat.test/soap", "<soap/>"))
                .thenReturn(new VerifactuTransportResponse(200, accepted()));

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        verify(attempts).recordSent(record.getId(), "<soap/>");
        verify(attempts).recordAccepted(record.getId(), accepted());
        assertXmlRequest();
    }

    @Test
    void marcaRechazadoSiAeatDevuelveErrorFuncional() {
        when(transport.send("https://aeat.test/soap", "<soap/>"))
                .thenReturn(new VerifactuTransportResponse(200, rejected()));

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(result.errorCode()).isEqualTo("1234");
        verify(attempts).recordRejected(record.getId(), "1234", "NIF incorrecto", rejected());
    }

    @Test
    void mantieneEnColaSiHayErrorDeRed() {
        when(transport.send("https://aeat.test/soap", "<soap/>"))
                .thenThrow(new VerifactuTransportException("sin conexion"));

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ENVIADO);
        assertThat(result.errorCode()).isEqualTo("NETWORK_ERROR");
        verify(attempts).recordSent(record.getId(), "<soap/>");
        verify(attempts, never()).recordRejected(any(), any(), any(), any());
        verify(attempts, never()).recordDefective(any(), any(), any(), any());
    }

    @Test
    void marcaDefectuosoSiElXmlNoCumpleXsdAntesDeEnviar() {
        doThrow(new IllegalArgumentException("XSD invalido"))
                .when(validator).validate("<sfLR:RegFactuSistemaFacturacion/>");

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
        assertThat(result.errorCode()).isEqualTo("INVALID_XSD");
        verify(attempts).recordDefective(
                record.getId(), "INVALID_XSD", "XSD invalido",
                "<sfLR:RegFactuSistemaFacturacion/>");
        verify(transport, never()).send(any(), any());
    }

    private void assertXmlRequest() {
        var request = ArgumentCaptor.forClass(VerifactuXmlBatchRequest.class);
        verify(xml).batchXml(request.capture());
        assertThat(request.getValue().records()).containsExactly(record);
        assertThat(request.getValue().systemInfo().systemName()).isEqualTo("TPV ERP");
    }

    private static String accepted() {
        return """
                <RespuestaRegFactuSistemaFacturacion>
                  <EstadoEnvio>Correcto</EstadoEnvio>
                </RespuestaRegFactuSistemaFacturacion>
                """;
    }

    private static String rejected() {
        return """
                <RespuestaRegFactuSistemaFacturacion>
                  <EstadoEnvio>Incorrecto</EstadoEnvio>
                  <CodigoErrorRegistro>1234</CodigoErrorRegistro>
                  <DescripcionErrorRegistro>NIF incorrecto</DescripcionErrorRegistro>
                </RespuestaRegFactuSistemaFacturacion>
                """;
    }

    private static FiscalRecord record() {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "001-260616-000001", LocalDate.of(2026, 6, 16),
                Instant.parse("2026-06-16T10:00:00Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                null, "A".repeat(64), "B".repeat(64), snapshot(),
                "1.0", "SHA-256", "0.0.1");
    }

    private static Map<String, Object> snapshot() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("baseTotal", new BigDecimal("10.00"));
        snapshot.put("impuestoTotal", new BigDecimal("2.10"));
        snapshot.put("total", new BigDecimal("12.10"));
        return snapshot;
    }
}

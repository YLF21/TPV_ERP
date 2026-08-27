package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    @Mock private VerifactuFirstSubmissionMarker firstSubmissions;
    @Mock private FiscalCorrectionCompletionService corrections;
    @Mock private FrozenFiscalIdentityResolver identities;
    @Mock private FiscalRecordArtifactRepository artifacts;
    @Mock private FiscalRuntimeProperties runtime;

    private FiscalRecord record;
    private VerifactuSubmissionService service;
    private static final String FROZEN_XML = """
            <sf:RegistroAlta xmlns:sf="https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd">
              <sf:IDFactura><sf:IDEmisorFactura>B12345674</sf:IDEmisorFactura></sf:IDFactura>
              <sf:NombreRazonEmisor>Empresa congelada</sf:NombreRazonEmisor>
            </sf:RegistroAlta>
            """;

    @BeforeEach
    void setUp() {
        record = record();
        lenient().when(endpoints.resolve(VerifactuEndpointMode.TEST))
                .thenReturn("https://aeat.test/soap");
        lenient().when(xml.frozenBatchXml(
                "Empresa congelada", "B12345674", java.util.List.of(FROZEN_XML)))
                .thenReturn("<sfLR:RegFactuSistemaFacturacion/>");
        lenient().when(soap.wrap("<sfLR:RegFactuSistemaFacturacion/>"))
                .thenReturn("<soap/>");
        lenient().when(runtime.endpointEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        lenient().when(artifacts.findByRecordId(record.getId()))
                .thenReturn(java.util.Optional.of(artifact(FROZEN_XML, sha256(FROZEN_XML))));
        lenient().when(identities.resolve(any(), any()))
                .thenReturn(new FrozenFiscalIdentityResolver.FrozenIssuerIdentity(
                        "Empresa congelada", "B12345674"));
        service = new VerifactuSubmissionService(
                xml, soap, endpoints, properties, transport, attempts,
                new VerifactuResponseParser(), validator, firstSubmissions, corrections,
                identities);
        service.setFrozenArtifacts(artifacts);
        service.setFiscalRuntimeProperties(runtime);
    }

    @Test
    void enviaXmlEntornoEIdentidadCongeladosAunqueCambieLaConfiguracionActual() {
        lenient().when(properties.current()).thenReturn(new VerifactuSubmissionProperties(
                VerifactuEndpointMode.PRODUCTION, "SIF cambiado", "OTRO"));
        when(transport.send(record.getCompanyId(), record.getInstallationId(),
                "https://aeat.test/soap", "<soap/>"))
                .thenReturn(new VerifactuTransportResponse(200, accepted()));

        service.submit(record);

        verify(xml).frozenBatchXml(
                "Empresa congelada", "B12345674", java.util.List.of(FROZEN_XML));
        verify(endpoints).resolve(VerifactuEndpointMode.TEST);
        verify(properties, never()).current();
    }

    @Test
    void rechazaEnviarRegistroNoVerifactuAunqueSeLlameDirectamente() {
        set(record, "fiscalMode", FiscalMode.NO_VERIFACTU);

        assertThatThrownBy(() -> service.submit(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERI*FACTU");
        verify(properties, never()).current();
        verify(transport, never()).send(any(), any(), any(), any());
    }

    @Test
    void rechazaEnviarSiFaltaElArtefactoCongelado() {
        when(artifacts.findByRecordId(record.getId())).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.submit(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artefacto fiscal congelado");
        verify(xml, never()).frozenBatchXml(any(), any(), any());
    }

    @Test
    void marcaDefectuosoElLegacySinIdentidadCongeladaInequivoca() {
        when(identities.resolve(any(), any()))
                .thenThrow(new UnresolvedLegacyFiscalIdentityException(
                        "El registro legacy no contiene evidencia fiscal inequivoca"));

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.DEFECTUOSO);
        assertThat(result.errorCode()).isEqualTo("LEGACY_IDENTITY_UNRESOLVED");
        assertThat(result.error()).contains("evidencia fiscal inequivoca");
        verify(attempts).recordDefective(
                record.getId(),
                "LEGACY_IDENTITY_UNRESOLVED",
                "IDENTIDAD_FISCAL_LEGACY_NO_RESUELTA: "
                        + "El registro legacy no contiene evidencia fiscal inequivoca",
                null);
        verify(xml, never()).frozenBatchXml(any(), any(), any());
        verify(transport, never()).send(any(), any(), any(), any());
    }

    @Test
    void rechazaXmlCongeladoAlteradoAntesDelReintento() {
        when(artifacts.findByRecordId(record.getId()))
                .thenReturn(java.util.Optional.of(artifact(FROZEN_XML + "alterado",
                        sha256(FROZEN_XML))));

        assertThatThrownBy(() -> service.submit(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("huella persistida");
        verify(transport, never()).send(any(), any(), any(), any());
    }

    @Test
    void rechazaReintentoSiElRuntimeNoCoincideConElEntornoCongelado() {
        when(runtime.endpointEnvironment()).thenReturn(FiscalEndpointEnvironment.PRODUCTION);

        assertThatThrownBy(() -> service.submit(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entorno congelado");
        verify(transport, never()).send(any(), any(), any(), any());
    }

    @Test
    void enviaElRegistroYMarcaAceptado() {
        when(transport.send(record.getCompanyId(), record.getInstallationId(),
                "https://aeat.test/soap", "<soap/>"))
                .thenReturn(new VerifactuTransportResponse(200, accepted()));

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
        verify(attempts).recordSent(record.getId(), "<soap/>");
        verify(attempts).recordAccepted(record.getId(), accepted());
        verify(firstSubmissions).mark(record);
        verify(corrections).accepted(record);
        assertXmlRequest();
    }

    @Test
    void marcaRechazadoSiAeatDevuelveErrorFuncional() {
        when(transport.send(record.getCompanyId(), record.getInstallationId(),
                "https://aeat.test/soap", "<soap/>"))
                .thenReturn(new VerifactuTransportResponse(200, rejected()));

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(result.errorCode()).isEqualTo("1234");
        verify(attempts).recordRejected(record.getId(), "1234", "NIF incorrecto", rejected());
        verify(firstSubmissions, never()).mark(any());
        verify(corrections, never()).accepted(any());
    }

    @Test
    void marcaPrimeraRemisionSiAeatAceptaConErrores() {
        when(transport.send(record.getCompanyId(), record.getInstallationId(),
                "https://aeat.test/soap", "<soap/>"))
                .thenReturn(new VerifactuTransportResponse(200, acceptedWithErrors()));

        var result = service.submit(record);

        assertThat(result.status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);
        verify(firstSubmissions).mark(record);
    }

    @Test
    void mantieneEnColaSiHayErrorDeRed() {
        when(transport.send(record.getCompanyId(), record.getInstallationId(),
                "https://aeat.test/soap", "<soap/>"))
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
        verify(xml).frozenBatchXml(
                "Empresa congelada", "B12345674", java.util.List.of(FROZEN_XML));
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

    private static String acceptedWithErrors() {
        return """
                <RespuestaRegFactuSistemaFacturacion>
                  <EstadoEnvio>ParcialmenteCorrecto</EstadoEnvio>
                  <CodigoErrorRegistro>2000</CodigoErrorRegistro>
                  <DescripcionErrorRegistro>Aceptado con errores</DescripcionErrorRegistro>
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

    private FiscalRecordArtifact artifact(String frozenXml, String hash) {
        var print = new FiscalPrintSnapshot(
                "1.0", "test", FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST,
                "https://prewww2.aeat.es/qr", "C".repeat(64), "QR tributario:",
                "VERI*FACTU", "PRUEBA");
        return new FiscalRecordArtifact(
                record.getId(), FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST,
                false, UUID.randomUUID(), "Empresa congelada", "B12345674",
                frozenXml, null, null, hash, print, Instant.parse("2026-06-16T10:00:00Z"));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}

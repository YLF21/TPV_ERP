package com.tpverp.saas.sync;

import static com.tpverp.saas.SaasTestData.fiscalAddress;
import static com.tpverp.saas.SaasTestData.validCif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tpverp.saas.admin.CreateCompanyRequest;
import com.tpverp.saas.admin.CreateCompanyResponse;
import com.tpverp.saas.fiscal.SaasFiscalStatusRepository;
import com.tpverp.saas.license.LicenseSaasLinkRequest;
import com.tpverp.saas.license.LicenseSaasLinkResponse;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyncEventApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SaasSyncEventRepository events;
    @Autowired SaasFiscalStatusRepository fiscalStatuses;

    @Test
    void guardaEventoSyncConTokenValido() throws Exception {
        CreateCompanyResponse company = createCompany("B44444543");
        LicenseSaasLinkResponse link = link(company, UUID.randomUUID());
        UUID eventId = UUID.randomUUID();

        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SyncEventRequest(
                                eventId,
                                company.companyId(),
                                company.storeId(),
                                UUID.randomUUID(),
                                "DOCUMENTO",
                                UUID.randomUUID(),
                                SyncOperation.CONFIRMAR,
                                Map.of("numero", "T-1")))))
                .andExpect(status().isOk());

        SaasSyncEvent persisted = events.findById(eventId).orElseThrow();
        assertThat(persisted.getProjectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.IGNORED);
        assertThat(persisted.getProjectedAt()).isNotNull();

        var statusResult = mvc.perform(get("/api/v1/admin/sync/projection-status")
                        .queryParam("companyId", company.companyId().toString())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        var projectionStatus = mapper.readValue(
                statusResult.getResponse().getContentAsString(),
                AdminSyncProjectionStatusView.class);
        assertThat(projectionStatus.ignored()).isEqualTo(1);
        assertThat(projectionStatus.received()).isZero();
        assertThat(projectionStatus.error()).isZero();
    }

    @Test
    void persisteComoProjectedUnInformeFiscalValido() throws Exception {
        CreateCompanyResponse company = createCompany("B30303030");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);
        UUID eventId = UUID.randomUUID();

        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SyncEventRequest(
                                eventId,
                                company.companyId(),
                                company.storeId(),
                                1L,
                                null,
                                "FISCAL_STATUS",
                                UUID.randomUUID(),
                                SyncOperation.ACTUALIZAR,
                                Map.of(
                                        "installationId", installationId.toString(),
                                        "companyId", company.companyId().toString(),
                                        "storeId", company.storeId().toString(),
                                        "effectiveMode", "VERIFACTU",
                                        "activationState", "ACTIVE",
                                        "modeVersion", 1,
                                        "runtimeClass", "SANDBOX",
                                        "endpointEnvironment", "TEST",
                                        "transportMode", "SIMULATED",
                                        "reportedAt", "2026-08-25T14:00:00Z")))))
                .andExpect(status().isOk());

        SaasSyncEvent persisted = events.findById(eventId).orElseThrow();
        assertThat(persisted.getProjectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(persisted.getProjectedAt()).isNotNull();
        assertThat(persisted.getProjectionError()).isNull();
    }

    @Test
    void eventoDuplicadoEsIdempotente() throws Exception {
        CreateCompanyResponse company = createCompany("B55555555");
        LicenseSaasLinkResponse link = link(company, UUID.randomUUID());
        UUID eventId = UUID.randomUUID();
        long before = events.count();
        SyncEventRequest request = new SyncEventRequest(
                eventId,
                company.companyId(),
                company.storeId(),
                null,
                "STOCK_MOVEMENT",
                UUID.randomUUID(),
                SyncOperation.CREAR,
                Map.of("cantidad", 3));

        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertThat(events.count()).isEqualTo(before + 1);
    }

    @Test
    void rechazaEventoSinToken() throws Exception {
        CreateCompanyResponse company = createCompany("B66666666");

        mvc.perform(post("/api/v1/sync/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SyncEventRequest(
                                UUID.randomUUID(),
                                company.companyId(),
                                company.storeId(),
                                null,
                                "DOCUMENTO",
                                UUID.randomUUID(),
                                SyncOperation.CONFIRMAR,
                                Map.of()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conservaComoErrorElInformeFiscalInvalidoSinRomperLaTransaccion() throws Exception {
        CreateCompanyResponse company = createCompany("B20202020");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);
        UUID eventId = UUID.randomUUID();
        SyncEventRequest request = new SyncEventRequest(
                eventId,
                company.companyId(),
                company.storeId(),
                1L,
                null,
                "FISCAL_STATUS",
                UUID.randomUUID(),
                SyncOperation.ACTUALIZAR,
                Map.of(
                        "installationId", installationId.toString(),
                        "companyId", company.companyId().toString(),
                        "storeId", company.storeId().toString(),
                        "effectiveMode", "VERIFACTU",
                        "activationState", "ACTIVE",
                        "modeVersion", 0,
                        "runtimeClass", "SANDBOX",
                        "endpointEnvironment", "TEST",
                        "transportMode", "SIMULATED",
                        "reportedAt", "fecha-invalida"));

        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        var persisted = events.findById(eventId).orElseThrow();
        assertThat(persisted.getProjectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.ERROR);
        assertThat(persisted.getProjectionError()).contains("reportedAt");
    }

    @Test
    void reproyectaUnDuplicadoEnErrorSinDuplicarElEstadoFiscal() throws Exception {
        CreateCompanyResponse company = createCompany("B21212121");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);
        UUID eventId = UUID.randomUUID();
        String reportedAt = Instant.now().minusSeconds(60).toString();
        SyncEventRequest request = new SyncEventRequest(
                eventId,
                company.companyId(),
                company.storeId(),
                1L,
                null,
                "FISCAL_STATUS",
                UUID.randomUUID(),
                SyncOperation.ACTUALIZAR,
                Map.of(
                        "installationId", installationId.toString(),
                        "companyId", company.companyId().toString(),
                        "storeId", company.storeId().toString(),
                        "effectiveMode", "VERIFACTU",
                        "activationState", "ACTIVE",
                        "modeVersion", 1,
                        "runtimeClass", "SANDBOX",
                        "endpointEnvironment", "TEST",
                        "transportMode", "SIMULATED",
                        "reportedAt", reportedAt));
        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        SaasSyncEvent failed = events.findById(eventId).orElseThrow();
        failed.markFailed("Fallo transitorio simulado");
        events.saveAndFlush(failed);

        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        SaasSyncEvent recovered = events.findById(eventId).orElseThrow();
        assertThat(recovered.getProjectionStatus())
                .isEqualTo(SaasSyncEvent.ProjectionStatus.PROJECTED);
        assertThat(recovered.getProjectedAt()).isNotNull();
        assertThat(recovered.getProjectionError()).isNull();
        assertThat(events.findAll())
                .filteredOn(event -> event.getEventId().equals(eventId))
                .hasSize(1);
        assertThat(fiscalStatuses.findAll())
                .filteredOn(status -> status.getSourceInstallationId().equals(installationId))
                .hasSize(1);
    }

    @Test
    void consultaEventosSyncPorTipoDesdeAdmin() throws Exception {
        CreateCompanyResponse company = createCompany("B77777777");
        LicenseSaasLinkResponse link = link(company, UUID.randomUUID());
        UUID saleEventId = UUID.randomUUID();
        UUID stockEventId = UUID.randomUUID();
        UUID cashEventId = UUID.randomUUID();

        sendEvent(link, new SyncEventRequest(
                saleEventId,
                company.companyId(),
                company.storeId(),
                UUID.randomUUID(),
                "DOCUMENTO",
                UUID.randomUUID(),
                SyncOperation.CONFIRMAR,
                Map.of("numero", "T-100", "total", "25.00")));
        sendEvent(link, new SyncEventRequest(
                stockEventId,
                company.companyId(),
                company.storeId(),
                null,
                "STOCK_MOVEMENT",
                UUID.randomUUID(),
                SyncOperation.CREAR,
                Map.of("cantidad", 3, "tipo", "AJUSTE")));
        sendEvent(link, new SyncEventRequest(
                cashEventId,
                company.companyId(),
                company.storeId(),
                UUID.randomUUID(),
                "CIERRE_CAJA",
                UUID.randomUUID(),
                SyncOperation.CERRAR,
                Map.of("descuadre", "0.00")));

        AdminSyncEventView[] sales = getAdminEvents("/api/v1/admin/sync/sales");
        AdminSyncEventView[] stock = getAdminEvents("/api/v1/admin/sync/stock-movements");
        AdminSyncEventView[] cash = getAdminEvents("/api/v1/admin/sync/cash-closures");

        assertThat(sales)
                .filteredOn(event -> event.eventId().equals(saleEventId))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.payload()).containsEntry("numero", "T-100");
                    assertThat(event.projectionStatus())
                            .isEqualTo(SaasSyncEvent.ProjectionStatus.IGNORED);
                    assertThat(event.projectedAt()).isNotNull();
                    assertThat(event.projectionError()).isNull();
                    assertThat(event.schemaVersion()).isEqualTo(1);
                });
        assertThat(stock)
                .extracting(AdminSyncEventView::eventId)
                .contains(stockEventId);
        assertThat(cash)
                .extracting(AdminSyncEventView::eventId)
                .contains(cashEventId);
    }

    @Test
    void consultaStockActualCalculadoDesdeMovimientos() throws Exception {
        CreateCompanyResponse company = createCompany("B77889900");
        LicenseSaasLinkResponse link = link(company, UUID.randomUUID());
        String productId = UUID.randomUUID().toString();
        String warehouseId = UUID.randomUUID().toString();

        sendEvent(link, new SyncEventRequest(
                UUID.randomUUID(),
                company.companyId(),
                company.storeId(),
                null,
                "STOCK_MOVEMENT",
                UUID.randomUUID(),
                SyncOperation.CREAR,
                Map.of(
                        "productoId", productId,
                        "almacenId", warehouseId,
                        "cantidad", 3)));
        sendEvent(link, new SyncEventRequest(
                UUID.randomUUID(),
                company.companyId(),
                company.storeId(),
                null,
                "STOCK_MOVEMENT",
                UUID.randomUUID(),
                SyncOperation.CREAR,
                Map.of(
                        "productoId", productId,
                        "almacenId", warehouseId,
                        "cantidad", "-1")));

        var result = mvc.perform(get("/api/v1/admin/sync/stock-current")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        AdminStockSnapshotView[] stock = mapper.readValue(
                result.getResponse().getContentAsString(),
                AdminStockSnapshotView[].class);

        assertThat(stock)
                .filteredOn(value -> value.productId().equals(productId)
                        && value.warehouseId().equals(warehouseId))
                .singleElement()
                .satisfies(value -> assertThat(value.quantity()).isEqualTo("2"));
    }

    @Test
    void filtraEventosAdminPorEmpresa() throws Exception {
        CreateCompanyResponse company = createCompany("B88990011");
        CreateCompanyResponse otherCompany = createCompany("B99001122");
        LicenseSaasLinkResponse link = link(company, UUID.randomUUID());
        LicenseSaasLinkResponse otherLink = link(otherCompany, UUID.randomUUID());
        UUID eventId = UUID.randomUUID();
        UUID otherEventId = UUID.randomUUID();

        sendEvent(link, new SyncEventRequest(
                eventId,
                company.companyId(),
                company.storeId(),
                null,
                "DOCUMENTO",
                UUID.randomUUID(),
                SyncOperation.CONFIRMAR,
                Map.of("numero", "T-FILTRADO")));
        sendEvent(otherLink, new SyncEventRequest(
                otherEventId,
                otherCompany.companyId(),
                otherCompany.storeId(),
                null,
                "DOCUMENTO",
                UUID.randomUUID(),
                SyncOperation.CONFIRMAR,
                Map.of("numero", "T-OTRO")));

        var result = mvc.perform(get("/api/v1/admin/sync/sales")
                        .queryParam("companyId", company.companyId().toString())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        AdminSyncEventView[] sales = mapper.readValue(
                result.getResponse().getContentAsString(),
                AdminSyncEventView[].class);

        assertThat(sales)
                .extracting(AdminSyncEventView::eventId)
                .contains(eventId)
                .doesNotContain(otherEventId);
    }

    @Test
    void resumeVentasDesdeDocumentosSinCompras() throws Exception {
        CreateCompanyResponse company = createCompany("B10101010");
        LicenseSaasLinkResponse link = link(company, UUID.randomUUID());

        sendEvent(link, documentEvent(company, "TICKET", "12.50", SyncOperation.CONFIRMAR));
        sendEvent(link, documentEvent(company, "FACTURA_VENTA", "7.50", SyncOperation.CONFIRMAR));
        sendEvent(link, documentEvent(company, "FACTURA_COMPRA", "100.00", SyncOperation.CONFIRMAR));
        sendEvent(link, documentEvent(company, "TICKET", "12.50", SyncOperation.ANULAR));

        var result = mvc.perform(get("/api/v1/admin/sync/sales-summary")
                        .queryParam("companyId", company.companyId().toString())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        AdminSalesSummaryView summary = mapper.readValue(
                result.getResponse().getContentAsString(),
                AdminSalesSummaryView.class);

        assertThat(summary.documentCount()).isEqualTo(2);
        assertThat(summary.total()).isEqualTo("20");
    }

    @Test
    void paginaEventosConCursorEstableYLimiteSeguro() throws Exception {
        CreateCompanyResponse company = createCompany("B12121212");
        LicenseSaasLinkResponse link = link(company, UUID.randomUUID());
        for (int index = 0; index < 205; index++) {
            sendEvent(link, documentEvent(company, "TICKET", "1.00", SyncOperation.CONFIRMAR));
        }

        JsonNode first = mapper.readTree(mvc.perform(get("/api/v1/admin/sync/sales/page")
                        .queryParam("companyId", company.companyId().toString())
                        .queryParam("size", "100")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode second = mapper.readTree(mvc.perform(get("/api/v1/admin/sync/sales/page")
                        .queryParam("companyId", company.companyId().toString())
                        .queryParam("size", "100")
                        .queryParam("cursor", first.get("nextCursor").asText())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(first.get("items")).hasSize(100);
        assertThat(second.get("items")).hasSize(100);
        assertThat(first.get("nextCursor").asText()).isNotEqualTo(second.get("nextCursor").asText());
        mvc.perform(get("/api/v1/admin/sync/sales/page")
                        .queryParam("size", "201")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paginaEstadoFiscalIncluyeTiendaSinReporteYAplicaFiltro() throws Exception {
        CreateCompanyResponse company = createCompany("B13131313");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);
        sendEvent(link, new SyncEventRequest(
                UUID.randomUUID(), company.companyId(), company.storeId(), 1L, null,
                "FISCAL_STATUS", UUID.randomUUID(), SyncOperation.ACTUALIZAR,
                Map.of("installationId", installationId.toString(), "companyId", company.companyId().toString(),
                        "storeId", company.storeId().toString(), "effectiveMode", "VERIFACTU",
                        "activationState", "ACTIVE", "modeVersion", 1, "runtimeClass", "SANDBOX",
                        "endpointEnvironment", "TEST", "transportMode", "SIMULATED",
                        "reportedAt", Instant.now().minusSeconds(60).toString())));

        mvc.perform(get("/api/v1/admin/fiscal-status/page")
                        .queryParam("companyId", company.companyId().toString())
                        .queryParam("effectiveMode", "VERIFACTU")
                        .queryParam("size", "1")
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());
    }

    private LicenseSaasLinkResponse link(CreateCompanyResponse company, UUID installationId) throws Exception {
        var result = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Link-Recovery-Token",
                                "recovery-token-0123456789abcdef0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(),
                                installationId,
                                "INST-1",
                                "public-key",
                                company.storeId(),
                                "001",
                                "DEMO-00000000",
                                "Empresa",
                                null,
                                null,
                                "Atlantic/Canary"))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), LicenseSaasLinkResponse.class);
    }

    private void sendEvent(LicenseSaasLinkResponse link, SyncEventRequest request) throws Exception {
        mvc.perform(post("/api/v1/sync/events")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private SyncEventRequest documentEvent(
            CreateCompanyResponse company,
            String type,
            String total,
            SyncOperation operation) {
        return new SyncEventRequest(
                UUID.randomUUID(),
                company.companyId(),
                company.storeId(),
                null,
                "DOCUMENTO",
                UUID.randomUUID(),
                operation,
                Map.of("tipo", type, "total", total));
    }

    private AdminSyncEventView[] getAdminEvents(String path) throws Exception {
        var result = mvc.perform(get(path)
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AdminSyncEventView[].class);
    }

    private CreateCompanyResponse createCompany(String taxId) throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCompanyRequest(
                                "Empresa",
                                validCif(taxId),
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                com.tpverp.saas.license.CommercialProfile.MAYORISTA,
                                fiscalAddress(),
                                "001",
                                "Tienda 1",
                                fiscalAddress(),
                                "Atlantic/Canary",
                                Instant.parse("2099-07-01T00:00:00Z"),
                                2,
                                1))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), CreateCompanyResponse.class);
    }

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}

package com.tpverp.saas.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.CreateCompanyRequest;
import com.tpverp.saas.admin.CreateCompanyResponse;
import com.tpverp.saas.license.LicenseSaasLinkRequest;
import com.tpverp.saas.license.LicenseSaasLinkResponse;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.time.Instant;
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

    @Test
    void guardaEventoSyncConTokenValido() throws Exception {
        CreateCompanyResponse company = createCompany("B44444444");
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

        assertThat(events.existsById(eventId)).isTrue();
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
                .satisfies(event -> assertThat(event.payload()).containsEntry("numero", "T-100"));
        assertThat(stock)
                .extracting(AdminSyncEventView::eventId)
                .contains(stockEventId);
        assertThat(cash)
                .extracting(AdminSyncEventView::eventId)
                .contains(cashEventId);
    }

    private LicenseSaasLinkResponse link(CreateCompanyResponse company, UUID installationId) throws Exception {
        var result = mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(),
                                installationId,
                                "INST-1",
                                "public-key",
                                company.storeId(),
                                "TIENDA-1",
                                "B00000000",
                                "Empresa"))))
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

    private AdminSyncEventView[] getAdminEvents(String path) throws Exception {
        var result = mvc.perform(get(path)
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AdminSyncEventView[].class);
    }

    private CreateCompanyResponse createCompany(String taxId) throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCompanyRequest(
                                "Empresa",
                                taxId,
                                TaxpayerType.SOCIEDAD,
                                TaxRegime.IGIC,
                                "TIENDA-1",
                                "Tienda 1",
                                Instant.parse("2027-07-01T00:00:00Z"),
                                2,
                                1))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), CreateCompanyResponse.class);
    }
}

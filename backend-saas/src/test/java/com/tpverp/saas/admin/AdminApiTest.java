package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.LicenseSaasLinkRequest;
import com.tpverp.saas.license.LicenseSaasLinkResponse;
import com.tpverp.saas.license.LicenseSaasStatus;
import com.tpverp.saas.license.LicenseSaasValidationRequest;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.time.Instant;
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
class AdminApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void creaEmpresaLicenciaYCodigoDeEnlace() throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("B12345678"))))
                .andExpect(status().isOk())
                .andReturn();

        CreateCompanyResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                CreateCompanyResponse.class);
        assertThat(response.companyId()).isNotNull();
        assertThat(response.storeId()).isNotNull();
        assertThat(response.licenseReference()).isEqualTo("LIC-B12345678-TIENDA-1");
        assertThat(response.pairingCode()).startsWith("TPV-");
    }

    @Test
    void rechazaAdminSinClave() throws Exception {
        mvc.perform(post("/api/v1/admin/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request("B87654321"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listaLicencias() throws Exception {
        CreateCompanyResponse company = createCompany("B11223344");

        var result = mvc.perform(get("/api/v1/admin/licenses")
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSummaryResponse[] licenses = mapper.readValue(
                result.getResponse().getContentAsString(),
                LicenseSummaryResponse[].class);
        assertThat(licenses)
                .extracting(LicenseSummaryResponse::licenseReference)
                .contains(company.licenseReference());
    }

    @Test
    void listaInstalacionesVinculadas() throws Exception {
        CreateCompanyResponse company = createCompany("B44556677");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);

        mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isOk());

        var result = mvc.perform(get("/api/v1/admin/installations")
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andReturn();

        InstallationSummaryResponse[] installations = mapper.readValue(
                result.getResponse().getContentAsString(),
                InstallationSummaryResponse[].class);
        assertThat(installations)
                .filteredOn(value -> value.installationId().equals(installationId))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.companyId()).isEqualTo(company.companyId());
                    assertThat(value.storeId()).isEqualTo(company.storeId());
                    assertThat(value.licenseReference()).isEqualTo(company.licenseReference());
                    assertThat(value.lastValidatedAt()).isNotNull();
                });
    }

    @Test
    void renuevaLicenciaYCambiaLimites() throws Exception {
        CreateCompanyResponse company = createCompany("B22334455");

        var result = mvc.perform(post("/api/v1/admin/licenses/{reference}/renew", company.licenseReference())
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RenewLicenseRequest(
                                Instant.parse("2028-01-01T00:00:00Z"),
                                5,
                                2))))
                .andExpect(status().isOk())
                .andReturn();

        AdminLicenseResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                AdminLicenseResponse.class);
        assertThat(response.status()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(response.validUntil()).isEqualTo(Instant.parse("2028-01-01T00:00:00Z"));
        assertThat(response.maxWindows()).isEqualTo(5);
        assertThat(response.maxPda()).isEqualTo(2);
    }

    @Test
    void regeneraCodigoDeEnlaceEInvalidaElAnterior() throws Exception {
        CreateCompanyResponse company = createCompany("B33445566");

        var result = mvc.perform(post("/api/v1/admin/licenses/{reference}/pairing-codes", company.licenseReference())
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andReturn();

        PairingCodeResponse response = mapper.readValue(
                result.getResponse().getContentAsString(),
                PairingCodeResponse.class);
        assertThat(response.pairingCode()).startsWith("TPV-");
        assertThat(response.pairingCode()).isNotEqualTo(company.pairingCode());

        mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(linkRequest(company.pairingCode(), company.storeId()))))
                .andExpect(status().isConflict());

        var linkResult = mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(linkRequest(response.pairingCode(), company.storeId()))))
                .andExpect(status().isOk())
                .andReturn();
        LicenseSaasLinkResponse link = mapper.readValue(
                linkResult.getResponse().getContentAsString(),
                LicenseSaasLinkResponse.class);
        assertThat(link.licenseReference()).isEqualTo(company.licenseReference());
    }

    private CreateCompanyRequest request(String taxId) {
        return new CreateCompanyRequest(
                "Empresa",
                taxId,
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "TIENDA-1",
                "Tienda 1",
                Instant.parse("2027-07-01T00:00:00Z"),
                2,
                1);
    }

    private CreateCompanyResponse createCompany(String taxId) throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("X-TPV-SaaS-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(taxId))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), CreateCompanyResponse.class);
    }

    private LicenseSaasLinkRequest linkRequest(String pairingCode, UUID storeId) {
        return new LicenseSaasLinkRequest(
                pairingCode,
                UUID.randomUUID(),
                "INST-1",
                "public-key",
                storeId,
                "TIENDA-1",
                "B00000000",
                "Empresa");
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
}

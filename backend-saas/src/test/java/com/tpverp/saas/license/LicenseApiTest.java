package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.CreateCompanyRequest;
import com.tpverp.saas.admin.CreateCompanyResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
class LicenseApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void vinculaInstalacionYValidaLicencia() throws Exception {
        CreateCompanyResponse company = createCompany("B11111111");
        UUID installationId = UUID.randomUUID();

        var linkResult = mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasLinkRequest(
                                company.pairingCode(),
                                installationId,
                                "INST-1",
                                "public-key",
                                company.storeId(),
                                "TIENDA-1",
                                "B11111111",
                                "Empresa"))))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSaasLinkResponse link = mapper.readValue(
                linkResult.getResponse().getContentAsString(),
                LicenseSaasLinkResponse.class);
        assertThat(link.licenseReference()).isEqualTo(company.licenseReference());
        assertThat(link.installationToken()).isNotBlank();

        var validationResult = mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSaasValidationResponse validation = mapper.readValue(
                validationResult.getResponse().getContentAsString(),
                LicenseSaasValidationResponse.class);
        assertThat(validation.status()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(validation.validUntil()).isEqualTo(Instant.parse("2027-07-01T00:00:00Z"));
    }

    @Test
    void validacionRechazaTokenIncorrecto() throws Exception {
        CreateCompanyResponse company = createCompany("B22222222");
        UUID installationId = UUID.randomUUID();
        link(company, installationId);

        mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void devuelveBloqueadaManualCuandoAdminBloquea() throws Exception {
        CreateCompanyResponse company = createCompany("B33333333");
        UUID installationId = UUID.randomUUID();
        LicenseSaasLinkResponse link = link(company, installationId);

        mvc.perform(post("/api/v1/admin/licenses/{reference}/block", company.licenseReference())
                        .header("Authorization", basic("admin", "admin")))
                .andExpect(status().isOk());

        var validationResult = mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Installation-Token", link.installationToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LicenseSaasValidationRequest(
                                installationId,
                                "INST-1",
                                company.storeId(),
                                company.licenseReference(),
                                "hash-local"))))
                .andExpect(status().isOk())
                .andReturn();

        LicenseSaasValidationResponse validation = mapper.readValue(
                validationResult.getResponse().getContentAsString(),
                LicenseSaasValidationResponse.class);
        assertThat(validation.status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
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

    private CreateCompanyResponse createCompany(String taxId) throws Exception {
        var result = mvc.perform(post("/api/v1/admin/companies")
                        .header("Authorization", basic("admin", "admin"))
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

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}

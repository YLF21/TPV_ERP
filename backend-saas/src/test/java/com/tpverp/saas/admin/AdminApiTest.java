package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import java.time.Instant;
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
}

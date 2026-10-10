package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.saas.admin.LoginAttemptLimiter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LicenseControllerStorePresentationTest {

    private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STORE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INSTALLATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant VALID_UNTIL = Instant.parse("2099-07-01T00:00:00Z");

    @Test
    void linkOnlyExposesInternalCodeForExactOptInHeader() throws Exception {
        var links = mock(LicenseLinkService.class);
        var validations = mock(LicenseValidationService.class);
        var attempts = mock(LoginAttemptLimiter.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LicenseController(links, validations, attempts)).build();
        var response = new LicenseSaasLinkResponse("LIC-1", COMPANY_ID, STORE_ID,
                "B12345674", "Empresa", null, "001", "Tienda", null,
                "Atlantic/Canary", VALID_UNTIL, LicenseSaasStatus.VALIDA, 2, 1,
                4, "B12345674", TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                CommercialProfile.MAYORISTA, LocalDate.of(2027, 1, 1), 3,
                Instant.parse("2026-01-01T00:00:00Z"), "token", "3512345");
        when(links.link(any(LicenseSaasLinkRequest.class), isNull(), isNull())).thenReturn(response);
        String body = "{\"pairingCode\":\"TPV-CODE01\",\"installationId\":\"" + INSTALLATION_ID
                + "\",\"installationReference\":\"INST-1\"}";

        var legacy = mvc.perform(post("/api/v1/license/link")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeCode").value("001"))
                .andExpect(jsonPath("$.taxId").value("B12345674"))
                .andReturn();
        assertThat(legacy.getResponse().getContentAsString()).doesNotContain("storeInternalCode");

        var optedIn = mvc.perform(post("/api/v1/license/link")
                        .header("X-TPV-Store-Presentation", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeInternalCode").value("3512345"))
                .andExpect(jsonPath("$.storeCode").value("001"))
                .andExpect(jsonPath("$.taxId").value("B12345674"))
                .andReturn();
        assertThat(optedIn.getResponse().getContentAsString()).contains("storeInternalCode");
    }

    @Test
    void validationOnlyExposesInternalCodeForExactOptInHeader() throws Exception {
        var links = mock(LicenseLinkService.class);
        var validations = mock(LicenseValidationService.class);
        var attempts = mock(LoginAttemptLimiter.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LicenseController(links, validations, attempts)).build();
        var response = new LicenseSaasValidationResponse(LicenseSaasStatus.VALIDA,
                VALID_UNTIL, LocalDate.of(2027, 1, 1), 3,
                Instant.parse("2026-01-01T00:00:00Z"), CommercialProfile.MAYORISTA,
                2, 1, 4, COMPANY_ID, STORE_ID, "LIC-1", "B12345674", "3512345");
        when(validations.validate(any(LicenseSaasValidationRequest.class), isNull())).thenReturn(response);
        String body = "{\"installationId\":\"" + INSTALLATION_ID
                + "\",\"installationReference\":\"INST-1\",\"licenseReference\":\"LIC-1\"}";

        var legacy = mvc.perform(post("/api/v1/license/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licenseReference").value("LIC-1"))
                .andExpect(jsonPath("$.taxId").value("B12345674"))
                .andReturn();
        assertThat(legacy.getResponse().getContentAsString()).doesNotContain("storeInternalCode");

        var otherVersion = mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Store-Presentation", "01")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(otherVersion.getResponse().getContentAsString()).doesNotContain("storeInternalCode");

        mvc.perform(post("/api/v1/license/validate")
                        .header("X-TPV-Store-Presentation", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeInternalCode").value("3512345"))
                .andExpect(jsonPath("$.licenseReference").value("LIC-1"))
                .andExpect(jsonPath("$.taxId").value("B12345674"));
    }
}

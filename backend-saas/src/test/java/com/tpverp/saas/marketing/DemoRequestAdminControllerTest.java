package com.tpverp.saas.marketing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoRequestAdminControllerTest {

    @Test
    void returnsTheBoundedInboxWithoutCachingPersonalData() throws Exception {
        DemoRequestService service = mock(DemoRequestService.class);
        UUID id = UUID.fromString("704802c5-47af-4f74-ae68-a90c56b8c3c6");
        Instant receivedAt = Instant.parse("2026-10-03T10:15:30Z");
        when(service.latest()).thenReturn(List.of(new DemoRequestView(
                id, DemoProduct.APP_PDA, List.of(DemoProduct.APP_PDA, DemoProduct.APP_SAAS),
                "Laura", "Mercado Centro", "laura@example.com",
                null, "Necesitamos inventarios", "es", "/producto/contacto",
                "https://partner.example/campaign", "partner", "referral", "retail-2026",
                "NEW", receivedAt, receivedAt)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestAdminController(service)).build();

        mvc.perform(get("/api/v1/admin/demo-requests"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].product").value("APP_PDA"))
                .andExpect(jsonPath("$[0].products[0]").value("APP_PDA"))
                .andExpect(jsonPath("$[0].products[1]").value("APP_SAAS"))
                .andExpect(jsonPath("$[0].products.length()").value(2))
                .andExpect(jsonPath("$[0].email").value("laura@example.com"))
                .andExpect(jsonPath("$[0].landingPath").value("/producto/contacto"))
                .andExpect(jsonPath("$[0].referrer").value("https://partner.example/campaign"))
                .andExpect(jsonPath("$[0].utmSource").value("partner"))
                .andExpect(jsonPath("$[0].utmMedium").value("referral"))
                .andExpect(jsonPath("$[0].utmCampaign").value("retail-2026"))
                .andExpect(jsonPath("$[0].status").value("NEW"));

        verify(service).latest();
    }
}

package com.tpverp.saas.marketing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DemoRequestControllerTest {

    @Test
    void acceptsAValidatedDemoRequestWithoutCachingTheReceipt() throws Exception {
        DemoRequestService service = mock(DemoRequestService.class);
        UUID id = UUID.fromString("704802c5-47af-4f74-ae68-a90c56b8c3c6");
        Instant receivedAt = Instant.parse("2026-10-03T10:15:30Z");
        when(service.submit(any())).thenReturn(new DemoRequestReceipt(id, receivedAt));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestController(service))
                .setControllerAdvice(new DemoRequestExceptionHandler()).build();

        mvc.perform(post("/api/v1/public/demo-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product":"APP_GESTION",
                                  "name":"Laura Garcia",
                                  "company":"Mercado Centro",
                                  "email":"laura@example.com",
                                  "phone":"600 000 000",
                                  "message":"Tenemos tres tiendas",
                                  "locale":"es",
                                  "privacyAccepted":true,
                                  "website":"",
                                  "landingPath":"/producto/contacto",
                                  "referrer":"https://search.example/",
                                  "utmSource":"newsletter",
                                  "utmMedium":"email",
                                  "utmCampaign":"lanzamiento-octubre"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.receivedAt").value("2026-10-03T10:15:30Z"));

        ArgumentCaptor<CreateDemoRequest> submitted = ArgumentCaptor.forClass(CreateDemoRequest.class);
        verify(service).submit(submitted.capture());
        org.assertj.core.api.Assertions.assertThat(submitted.getValue()).satisfies(request -> {
            org.assertj.core.api.Assertions.assertThat(request.landingPath()).isEqualTo("/producto/contacto");
            org.assertj.core.api.Assertions.assertThat(request.referrer()).isEqualTo("https://search.example/");
            org.assertj.core.api.Assertions.assertThat(request.utmSource()).isEqualTo("newsletter");
            org.assertj.core.api.Assertions.assertThat(request.utmMedium()).isEqualTo("email");
            org.assertj.core.api.Assertions.assertThat(request.utmCampaign()).isEqualTo("lanzamiento-octubre");
        });
    }

    @Test
    void rejectsInvalidEmailAndMissingConsentBeforeCallingTheService() throws Exception {
        DemoRequestService service = mock(DemoRequestService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestController(service))
                .setControllerAdvice(new DemoRequestExceptionHandler()).build();

        mvc.perform(post("/api/v1/public/demo-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product":"APP_VENTA",
                                  "name":"Laura",
                                  "company":"Mercado Centro",
                                  "email":"not-an-email",
                                  "locale":"es",
                                  "privacyAccepted":false
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(service, never()).submit(any());
    }

    @Test
    void keepsAcceptingExistingPayloadsWithoutAttribution() throws Exception {
        DemoRequestService service = mock(DemoRequestService.class);
        when(service.submit(any())).thenReturn(new DemoRequestReceipt(
                UUID.fromString("704802c5-47af-4f74-ae68-a90c56b8c3c6"),
                Instant.parse("2026-10-03T10:15:30Z")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestController(service))
                .setControllerAdvice(new DemoRequestExceptionHandler()).build();

        mvc.perform(post("/api/v1/public/demo-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product":"APP_VENTA",
                                  "name":"Laura",
                                  "company":"Mercado Centro",
                                  "email":"laura@example.com",
                                  "locale":"es",
                                  "privacyAccepted":true
                                }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateDemoRequest> submitted = ArgumentCaptor.forClass(CreateDemoRequest.class);
        verify(service).submit(submitted.capture());
        org.assertj.core.api.Assertions.assertThat(submitted.getValue().landingPath()).isNull();
        org.assertj.core.api.Assertions.assertThat(submitted.getValue().utmCampaign()).isNull();
        org.assertj.core.api.Assertions.assertThat(submitted.getValue().selectedProducts())
                .containsExactly(DemoProduct.APP_VENTA);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"product\":\"APP_GESTION\",\"products\":[\"APP_GESTION\",\"APP_SAAS\"],",
            "\"products\":[\"APP_SAAS\",\"APP_GESTION\"],"
    })
    void acceptsMultipleProductsWithOrWithoutTheLegacySummary(String selection) throws Exception {
        CreateDemoRequest request = submitSelection(selection);
        org.assertj.core.api.Assertions.assertThat(request.selectedProducts())
                .containsExactly(DemoProduct.APP_GESTION, DemoProduct.APP_SAAS);
    }

    @Test
    void acceptsTheWholePackageAsAllFourApplications() throws Exception {
        CreateDemoRequest request = submitSelection("""
                "product":"APP_VENTA",
                "products":["APP_VENTA","APP_GESTION","APP_PDA","APP_SAAS"],
                """);
        org.assertj.core.api.Assertions.assertThat(request.selectedProducts())
                .containsExactlyElementsOf(List.of(DemoProduct.values()));
    }

    @Test
    void acceptsASingleSelectedApplicationInTheNewArrayContract() throws Exception {
        CreateDemoRequest request = submitSelection("""
                "product":"APP_PDA", "products":["APP_PDA"],
                """);
        org.assertj.core.api.Assertions.assertThat(request.selectedProducts()).containsExactly(DemoProduct.APP_PDA);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "\"products\":[],",
            "\"product\":\"APP_VENTA\",\"products\":[],",
            "\"products\":null,",
            "\"products\":[null],",
            "\"products\":[\"APP_VENTA\",null],",
            "\"products\":[\"APP_VENTA\",\"APP_VENTA\"],",
            "\"products\":[\"APP_VENTA\",\"APP_GESTION\",\"APP_PDA\",\"APP_SAAS\",\"APP_VENTA\"],",
            "\"products\":[\"UNKNOWN_PRODUCT\"],",
            "\"product\":\"APP_VENTA\",\"products\":[\"APP_SAAS\"],",
            "\"product\":\"APP_SAAS\",\"products\":[\"APP_GESTION\",\"APP_SAAS\"],"
    })
    void rejectsInvalidOrConflictingSelectionsBeforeCallingTheService(String selection) throws Exception {
        DemoRequestService service = mock(DemoRequestService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestController(service))
                .setControllerAdvice(new DemoRequestExceptionHandler()).build();

        mvc.perform(post("/api/v1/public/demo-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(selection)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(service, never()).submit(any());
    }

    private static CreateDemoRequest submitSelection(String selection) throws Exception {
        DemoRequestService service = mock(DemoRequestService.class);
        when(service.submit(any())).thenReturn(new DemoRequestReceipt(UUID.randomUUID(), Instant.now()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestController(service))
                .setControllerAdvice(new DemoRequestExceptionHandler()).build();
        mvc.perform(post("/api/v1/public/demo-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload(selection)))
                .andExpect(status().isCreated());
        ArgumentCaptor<CreateDemoRequest> submitted = ArgumentCaptor.forClass(CreateDemoRequest.class);
        verify(service).submit(submitted.capture());
        return submitted.getValue();
    }

    private static String validPayload(String selection) {
        return """
                {%s "name":"Laura", "company":"Mercado Centro", "email":"laura@example.com",
                "locale":"es", "privacyAccepted":true}
                """.formatted(selection);
    }

    @Test
    void rejectsAnUnknownProductWithoutEchoingTheSubmittedValue() throws Exception {
        DemoRequestService service = mock(DemoRequestService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestController(service))
                .setControllerAdvice(new DemoRequestExceptionHandler()).build();

        mvc.perform(post("/api/v1/public/demo-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product":"UNKNOWN_PRODUCT",
                                  "name":"Laura",
                                  "company":"Mercado Centro",
                                  "email":"laura@example.com",
                                  "locale":"es",
                                  "privacyAccepted":true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("La solicitud contiene campos invalidos"));

        verify(service, never()).submit(any());
    }

    @Test
    void rejectsEachOversizedAttributionFieldBeforeCallingTheService() throws Exception {
        Map<String, Integer> limits = Map.of(
                "landingPath", 500,
                "referrer", 1000,
                "utmSource", 160,
                "utmMedium", 160,
                "utmCampaign", 200);

        for (var entry : limits.entrySet()) {
            DemoRequestService service = mock(DemoRequestService.class);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoRequestController(service))
                    .setControllerAdvice(new DemoRequestExceptionHandler()).build();

            mvc.perform(post("/api/v1/public/demo-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "product":"APP_VENTA",
                                      "name":"Laura",
                                      "company":"Mercado Centro",
                                      "email":"laura@example.com",
                                      "locale":"es",
                                      "privacyAccepted":true,
                                      "%s":"%s"
                                    }
                                    """.formatted(entry.getKey(), "x".repeat(entry.getValue() + 1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verify(service, never()).submit(any());
        }
    }
}

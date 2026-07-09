package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(ProductControllerContractTest.MethodSecurityConfiguration.class)
class ProductControllerContractTest {

    private static final UUID FAMILY_ID = UUID.randomUUID();
    private static final UUID TAX_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CatalogService service;

    @MockitoBean
    private ProductImageService images;

    @Test
    void createsProductAndWritesAJsonResponseForSalesUsers() throws Exception {
        var product = new Product(
                UUID.randomUUID(),
                FAMILY_ID,
                null,
                TAX_ID,
                ProductType.UNIT,
                DiscountType.NORMAL,
                "Cafe",
                "Descripcion",
                "Comentario",
                new BigDecimal("1.20"),
                true);
        product.replaceIdentifier(IdentifierType.CODIGO, "A001");
        product.setPrice(PriceTier.VENTA, new BigDecimal("2.40"));
        product.configurePriceUse(PriceUseMode.OFFER_DISCOUNT, new BigDecimal("10.00"));
        product.setPrice(PriceTier.OFERTA, new BigDecimal("2.16"));
        product.configureOffer(true, LocalDate.of(2026, 7, 1), null);

        when(service.createProduct(any(CatalogService.ProductRequest.class))).thenReturn(product);

        mvc.perform(post("/api/v1/products")
                        .with(user("seller").authorities(() -> VENTA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "familyId": "%s",
                                  "taxId": "%s",
                                  "productType": "UNIT",
                                  "discountType": "NORMAL",
                                  "priceUseMode": "OFFER_DISCOUNT",
                                  "name": "Cafe",
                                  "description": "Descripcion",
                                  "comments": "Comentario",
                                  "purchasePrice": 1.20,
                                  "taxesIncluded": true,
                                  "code": "A001",
                                  "salePrice": 2.40,
                                  "offerPrice": 2.16,
                                  "offerDiscountPercent": 10.00,
                                  "offerActive": false,
                                  "offerFrom": "2026-07-01"
                                }
                                """.formatted(FAMILY_ID, TAX_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId().toString()))
                .andExpect(jsonPath("$.code").value("A001"))
                .andExpect(jsonPath("$.salePrice").value(2.40))
                .andExpect(jsonPath("$.priceUseMode").value("OFFER_DISCOUNT"))
                .andExpect(jsonPath("$.offerDiscountPercent").value(10.00));
    }

    @Test
    void uploadsProductImageAndWritesAJsonResponseForSalesUsers() throws Exception {
        UUID productId = UUID.randomUUID();
        var product = new Product(
                UUID.randomUUID(),
                FAMILY_ID,
                null,
                TAX_ID,
                ProductType.UNIT,
                DiscountType.NORMAL,
                "Cafe",
                null,
                null,
                new BigDecimal("1.20"),
                true);
        product.replaceIdentifier(IdentifierType.CODIGO, "A001");
        product.setPrice(PriceTier.VENTA, new BigDecimal("2.40"));
        product.attachImage(UUID.randomUUID(), ProductImageService.CONTENT_TYPE, 120L, "abcd");

        when(images.upload(any(UUID.class), any(byte[].class))).thenReturn(product);

        var file = new MockMultipartFile("file", "product.png", "image/png", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/products/{productId}/image", productId)
                        .file(file)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .with(user("seller").authorities(() -> VENTA))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId().toString()))
                .andExpect(jsonPath("$.imageType").value(ProductImageService.CONTENT_TYPE))
                .andExpect(jsonPath("$.imageSize").value(120));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

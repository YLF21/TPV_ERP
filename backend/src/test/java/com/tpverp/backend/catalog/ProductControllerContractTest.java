package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    @MockitoBean
    private SaleProductCatalogService saleCatalog;

    @Test
    void saleListIncludesAuthoritativeTaxSnapshot() throws Exception {
        when(saleCatalog.products()).thenReturn(List.of(new SaleProductView(
                UUID.randomUUID(),
                true,
                "A001",
                null,
                null,
                "Cafe",
                new BigDecimal("2.40"),
                null,
                null,
                null,
                PriceUseMode.NORMAL,
                DiscountType.NORMAL,
                false,
                null,
                null,
                true,
                TAX_ID,
                new BigDecimal("21.00"),
                "IVA")));

        mvc.perform(get("/api/v1/products/sale")
                        .with(user("seller").authorities(() -> VENTA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taxPercentage").value(21.00))
                .andExpect(jsonPath("$[0].taxRegime").value("IVA"));
    }

    @Test
    void publicProductReadDoesNotExposePurchaseFields() throws Exception {
        var product = productWithPurchasePrice();
        when(service.product(product.getId())).thenReturn(product);

        mvc.perform(get("/api/v1/products/{productId}", product.getId())
                        .with(user("seller").authorities(() -> VENTA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchasePrice").doesNotExist())
                .andExpect(jsonPath("$.purchaseDiscountPercent").doesNotExist())
                .andExpect(jsonPath("$.salePrice").value(2.40));
    }

    @Test
    void managementProductReadExposesPurchaseFieldsForProductManagers() throws Exception {
        var product = productWithPurchasePrice();
        product.configurePurchaseDiscount(new BigDecimal("10.00"));
        when(service.product(product.getId())).thenReturn(product);

        mvc.perform(get("/api/v1/products/management/{productId}", product.getId())
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchasePrice").value(1.20))
                .andExpect(jsonPath("$.purchaseDiscountPercent").value(10.00))
                .andExpect(jsonPath("$.salePrice").value(2.40));
    }

    @Test
    void createsProductAndWritesAJsonResponseForProductManagers() throws Exception {
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
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
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
    void uploadsProductImageAndWritesAJsonResponseForProductManagers() throws Exception {
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
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId().toString()))
                .andExpect(jsonPath("$.imageType").value(ProductImageService.CONTENT_TYPE))
                .andExpect(jsonPath("$.imageSize").value(120));
    }

    @Test
    void changesProductActiveStateWithProductManagementPermission() throws Exception {
        UUID productId = UUID.randomUUID();
        var product = new Product(
                UUID.randomUUID(), FAMILY_ID, null, TAX_ID,
                "Cafe", null, BigDecimal.ZERO, true);
        product.deactivate();
        when(service.setProductActive(productId, false)).thenReturn(product);

        mvc.perform(patch("/api/v1/products/{productId}/active", productId)
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(service).setProductActive(productId, false);
    }

    @Test
    void salesPermissionAloneCannotChangeProductActiveState() throws Exception {
        mvc.perform(patch("/api/v1/products/{productId}/active", UUID.randomUUID())
                        .with(user("seller").authorities(() -> VENTA))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }

    private static Product productWithPurchasePrice() {
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
        return product;
    }
}

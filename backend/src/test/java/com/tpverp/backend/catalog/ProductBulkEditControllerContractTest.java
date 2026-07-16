package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@WebMvcTest(ProductBulkEditController.class)
@Import(ProductBulkEditControllerContractTest.MethodSecurityConfiguration.class)
class ProductBulkEditControllerContractTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProductBulkEditService service;
    @MockitoBean private ProductSupplierService productSuppliers;
    @MockitoBean private ProductBulkImportService imports;
    @MockitoBean private ProductBulkXlsxService xlsx;
    @MockitoBean private ProductBulkEditImageService images;

    @Test
    void productManagersCanListSuppliersAndAllTheirProducts() throws Exception {
        UUID supplierId = UUID.randomUUID();
        when(productSuppliers.listSupplierOptions()).thenReturn(List.of());
        when(productSuppliers.listSupplierProducts(supplierId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/product-bulk-edits/suppliers")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/product-bulk-edits/suppliers/" + supplierId + "/products")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk());

        verify(productSuppliers).listSupplierOptions();
        verify(productSuppliers).listSupplierProducts(supplierId);
    }

    @Test
    void productManagersCanLoadProductSupplierRelationsWithTheCompleteShape() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Instant lastEntryAt = Instant.parse("2026-07-11T10:15:30Z");
        when(productSuppliers.listForCurrentStore()).thenReturn(List.of(
                new ProductSupplierService.StoreProductSupplierView(
                        productId,
                        supplierId,
                        "00000042",
                        "Proveedor Fiscal",
                        "Proveedor Tienda",
                        "B00000042",
                        true,
                        "REF-42",
                        true,
                        true,
                        new BigDecimal("100.00"),
                        new BigDecimal("12.50"),
                        new BigDecimal("87.50"),
                        lastEntryAt)));

        mvc.perform(get("/api/v1/product-bulk-edits/product-suppliers")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$[0].supplierId").value(supplierId.toString()))
                .andExpect(jsonPath("$[0].supplierCode").value("00000042"))
                .andExpect(jsonPath("$[0].legalName").value("Proveedor Fiscal"))
                .andExpect(jsonPath("$[0].tradeName").value("Proveedor Tienda"))
                .andExpect(jsonPath("$[0].documentNumber").value("B00000042"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].supplierReference").value("REF-42"))
                .andExpect(jsonPath("$[0].principal").value(true))
                .andExpect(jsonPath("$[0].lastSupplier").value(true))
                .andExpect(jsonPath("$[0].grossPurchasePrice").value(100.00))
                .andExpect(jsonPath("$[0].purchaseDiscount").value(12.50))
                .andExpect(jsonPath("$[0].netPurchasePrice").value(87.50))
                .andExpect(jsonPath("$[0].lastEntryAt").value(lastEntryAt.toString()));

        verify(productSuppliers).listForCurrentStore();
    }

    @Test
    void productSupplierRelationsRequireProductManagementPermission() throws Exception {
        mvc.perform(get("/api/v1/product-bulk-edits/product-suppliers")
                        .with(user("unauthorized")))
                .andExpect(status().isForbidden());

        verify(productSuppliers, never()).listForCurrentStore();
    }

    @Test
    void usersWithoutProductManagementPermissionAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/product-bulk-edits/suppliers")
                        .with(user("unauthorized")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/product-bulk-edits/" + UUID.randomUUID() + "/images")
                        .with(user("unauthorized")))
                .andExpect(status().isForbidden());
    }

    @Test
    void productManagersCanCreateUpdateAndApplyBulkEdits() throws Exception {
        UUID id = UUID.randomUUID();
        var manager = user("manager").authorities(() -> GESTION_PRODUCTO);

        mvc.perform(post("/api/v1/product-bulk-edits")
                        .with(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Revision","content":[]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/product-bulk-edits/" + id)
                        .with(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"name":"Revision actualizada","content":[]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/product-bulk-edits/" + id + "/apply")
                        .with(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"updates":[],"supplierAssignments":[],"content":[]}
                                """))
                .andExpect(status().isOk());

        verify(service).create(
                any(ProductBulkEditService.ProductBulkCreateRequest.class), any());
        verify(service).update(
                eq(id), any(ProductBulkEditService.ProductBulkUpdateRequest.class), any());
        verify(service).apply(
                eq(id), any(ProductBulkEditService.ProductBulkApplyRequest.class), any());
    }

    @Test
    void createAcceptsPersistedProductFieldsUsedByTheBulkEditor() throws Exception {
        mvc.perform(post("/api/v1/product-bulk-edits")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Revision",
                                  "content":[{
                                    "id":"row-1",
                                    "selected":false,
                                    "query":"P-1",
                                    "product":{
                                      "productId":"11111111-1111-1111-1111-111111111111",
                                      "version":1,
                                      "quantity":"0",
                                      "totalQuantity":"0",
                                      "packageQuantity":"6"
                                    },
                                    "draft":{},
                                    "suppliers":[]
                                  }]
                                }
                                """))
                .andExpect(status().isOk());

        verify(service).create(
                any(ProductBulkEditService.ProductBulkCreateRequest.class), any());
    }

    @Test
    void updateApplyAndDeleteRequireTheCurrentVersion() throws Exception {
        UUID id = UUID.randomUUID();
        var manager = user("manager").authorities(() -> GESTION_PRODUCTO);

        mvc.perform(put("/api/v1/product-bulk-edits/" + id)
                        .with(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Revision","content":[]}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/product-bulk-edits/" + id + "/apply")
                        .with(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"updates":[],"supplierAssignments":[],"content":[]}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/product-bulk-edits/" + id)
                        .with(manager)
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void productManagersAndAdminsCanRenameAppliedListsInPlace() throws Exception {
        UUID id = UUID.randomUUID();
        UUID seriesId = UUID.randomUUID();
        ProductBulkEditView view = new ProductBulkEditView(
                id,
                "20260712001",
                seriesId,
                1,
                null,
                "Revision aplicada",
                ProductBulkEditStatus.APPLIED,
                List.of(),
                4L,
                UUID.randomUUID(),
                "creator",
                Instant.parse("2026-07-12T08:00:00Z"),
                UUID.randomUUID(),
                "manager",
                Instant.parse("2026-07-12T09:00:00Z"),
                UUID.randomUUID(),
                "applier",
                Instant.parse("2026-07-12T08:30:00Z"),
                List.of());
        when(service.rename(
                eq(id),
                eq(new ProductBulkEditService.ProductBulkRenameRequest(3L, "Revision aplicada")),
                any()))
                .thenReturn(view);

        mvc.perform(patch("/api/v1/product-bulk-edits/{id}/name", id)
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"name\":\"Revision aplicada\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Revision aplicada"))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.version").value(4));

        mvc.perform(patch("/api/v1/product-bulk-edits/{id}/name", id)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"name\":\"Revision aplicada\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void renameRequiresVersionAndNonBlankName() throws Exception {
        UUID id = UUID.randomUUID();
        var manager = user("manager").authorities(() -> GESTION_PRODUCTO);

        mvc.perform(patch("/api/v1/product-bulk-edits/{id}/name", id)
                        .with(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Revision\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/product-bulk-edits/{id}/name", id)
                        .with(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renameIsForbiddenWithoutProductManagementPermission() throws Exception {
        mvc.perform(patch("/api/v1/product-bulk-edits/{id}/name", UUID.randomUUID())
                        .with(user("unauthorized"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Revision\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void productManagersCanListAndOpenPurchaseInvoices() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        when(imports.purchaseInvoices()).thenReturn(List.of());
        when(imports.purchaseInvoiceProducts(invoiceId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/product-bulk-edits/purchase-invoices")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/product-bulk-edits/purchase-invoices/" + invoiceId + "/products")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk());

        verify(imports).purchaseInvoices();
        verify(imports).purchaseInvoiceProducts(invoiceId);
    }

    @Test
    void productManagersCanListAndOpenPurchaseDeliveryNotes() throws Exception {
        UUID deliveryNoteId = UUID.randomUUID();
        when(imports.purchaseDeliveryNotes()).thenReturn(List.of());
        when(imports.purchaseDeliveryNoteProducts(deliveryNoteId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/product-bulk-edits/purchase-delivery-notes")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/product-bulk-edits/purchase-delivery-notes/"
                        + deliveryNoteId + "/products")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO)))
                .andExpect(status().isOk());

        verify(imports).purchaseDeliveryNotes();
        verify(imports).purchaseDeliveryNoteProducts(deliveryNoteId);
    }

    @Test
    void productManagersCanExportTypedXlsxContent() throws Exception {
        mvc.perform(post("/api/v1/product-bulk-edits/export")
                        .with(user("manager").authorities(() -> GESTION_PRODUCTO))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"productos-edicion-masiva.xlsx\""));
    }

    @Test
    void productManagersCanSynchronizeListAndDownloadStagedImages() throws Exception {
        UUID draftId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(images.sync(
                eq(draftId),
                any(ProductBulkEditImageService.ProductBulkEditImageSyncRequest.class),
                any(),
                any()))
                .thenReturn(new ProductBulkEditImageSyncView(4L, List.of()));
        when(images.read(draftId, imageId)).thenReturn(
                new ProductBulkEditImageService.ProductBulkEditImageContent(
                        "foto producto.png", "image/png", new byte[] {1, 2, 3}));
        MockMultipartFile manifest = new MockMultipartFile(
                "manifest",
                "manifest.json",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {"version":3,"images":[
                          {"id":null,"productId":null,"fileIndex":0}
                        ]}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile(
                "files", "foto.png", "image/png", new byte[] {1, 2, 3});
        var manager = user("manager").authorities(() -> GESTION_PRODUCTO);

        mvc.perform(get("/api/v1/product-bulk-edits/" + draftId + "/images")
                        .with(manager))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mvc.perform(multipart("/api/v1/product-bulk-edits/" + draftId + "/images")
                        .file(manifest)
                        .file(file)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .with(manager)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"version\":4,\"images\":[]}"));

        mvc.perform(get("/api/v1/product-bulk-edits/" + draftId
                        + "/images/" + imageId + "/content")
                        .with(manager))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("foto%20producto.png")));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}

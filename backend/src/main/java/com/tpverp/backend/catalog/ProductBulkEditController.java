package com.tpverp.backend.catalog;

import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/product-bulk-edits")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_PRODUCTO')")
public class ProductBulkEditController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ProductBulkEditService service;
    private final ProductSupplierService productSuppliers;
    private final ProductBulkImportService imports;
    private final ProductBulkXlsxService xlsx;
    private final ProductBulkEditImageService images;

    public ProductBulkEditController(
            ProductBulkEditService service,
            ProductSupplierService productSuppliers,
            ProductBulkImportService imports,
            ProductBulkXlsxService xlsx,
            ProductBulkEditImageService images) {
        this.service = service;
        this.productSuppliers = productSuppliers;
        this.imports = imports;
        this.xlsx = xlsx;
        this.images = images;
    }

    @GetMapping
    public List<ProductBulkEditView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ProductBulkEditView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/images")
    public List<ProductBulkEditImageView> images(@PathVariable UUID id) {
        return images.list(id);
    }

    @GetMapping("/{id}/images/{imageId}/content")
    public ResponseEntity<byte[]> imageContent(
            @PathVariable UUID id,
            @PathVariable UUID imageId) {
        ProductBulkEditImageService.ProductBulkEditImageContent image = images.read(id, imageId);
        byte[] content = image.content();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(content.length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(image.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(content);
    }

    @GetMapping("/suppliers")
    public List<ProductSupplierService.SupplierOptionView> suppliers() {
        return productSuppliers.listSupplierOptions();
    }

    @GetMapping("/suppliers/{supplierId}/products")
    public List<ProductSupplierService.SupplierProductView> supplierProducts(
            @PathVariable UUID supplierId) {
        return productSuppliers.listSupplierProducts(supplierId);
    }

    @GetMapping("/product-suppliers")
    public List<ProductSupplierService.StoreProductSupplierView> productSuppliers() {
        return productSuppliers.listForCurrentStore();
    }

    @GetMapping("/purchase-invoices")
    public List<ProductBulkImportService.PurchaseDocumentOptionView> purchaseInvoices() {
        return imports.purchaseInvoices();
    }

    @GetMapping("/purchase-invoices/{invoiceId}/products")
    public List<ProductBulkImportService.PurchaseDocumentProductView> purchaseInvoiceProducts(
            @PathVariable UUID invoiceId) {
        return imports.purchaseInvoiceProducts(invoiceId);
    }

    @GetMapping("/purchase-delivery-notes")
    public List<ProductBulkImportService.PurchaseDocumentOptionView> purchaseDeliveryNotes() {
        return imports.purchaseDeliveryNotes();
    }

    @GetMapping("/purchase-delivery-notes/{deliveryNoteId}/products")
    public List<ProductBulkImportService.PurchaseDocumentProductView> purchaseDeliveryNoteProducts(
            @PathVariable UUID deliveryNoteId) {
        return imports.purchaseDeliveryNoteProducts(deliveryNoteId);
    }

    @PostMapping
    public ProductBulkEditView create(
            @Valid @RequestBody ProductBulkEditService.ProductBulkCreateRequest request,
            Authentication authentication) {
        return service.create(request, authentication);
    }

    @PostMapping(path = "/export", produces =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(
            @Valid @RequestBody ProductBulkXlsxContent request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xlsx.export(request, output);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"productos-edicion-masiva.xlsx\"")
                .body(output.toByteArray());
    }

    @PutMapping("/{id}")
    public ProductBulkEditView update(
            @PathVariable UUID id,
            @Valid @RequestBody ProductBulkEditService.ProductBulkUpdateRequest request,
            Authentication authentication) {
        return service.update(id, request, authentication);
    }

    @PatchMapping("/{id}/name")
    public ProductBulkEditView rename(
            @PathVariable UUID id,
            @Valid @RequestBody ProductBulkEditService.ProductBulkRenameRequest request,
            Authentication authentication) {
        return service.rename(id, request, authentication);
    }

    @PutMapping(path = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductBulkEditImageSyncView syncImages(
            @PathVariable UUID id,
            @Valid @RequestPart("manifest")
            ProductBulkEditImageService.ProductBulkEditImageSyncRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            Authentication authentication) {
        return images.sync(id, request, imageUploads(files), authentication);
    }

    @PostMapping("/{id}/apply")
    public ProductBulkEditView apply(
            @PathVariable UUID id,
            @Valid @RequestBody ProductBulkEditService.ProductBulkApplyRequest request,
            Authentication authentication) {
        return service.apply(id, request, authentication);
    }

    @PostMapping("/{id}/comments")
    public ProductBulkEditView addComment(
            @PathVariable UUID id,
            @Valid @RequestBody ProductBulkEditService.ProductBulkCommentRequest request,
            Authentication authentication) {
        return service.addComment(id, request, authentication);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestParam long version,
            Authentication authentication) {
        service.delete(id, version, authentication);
        return ResponseEntity.noContent().build();
    }

    private static List<ProductBulkEditImageService.ProductBulkEditImageUpload> imageUploads(
            List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<ProductBulkEditImageService.ProductBulkEditImageUpload> uploads =
                new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            try {
                uploads.add(new ProductBulkEditImageService.ProductBulkEditImageUpload(
                        file.getOriginalFilename(), file.getContentType(), file.getBytes()));
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "No se pudo recibir una imagen de edicion masiva", exception);
            }
        }
        return List.copyOf(uploads);
    }
}

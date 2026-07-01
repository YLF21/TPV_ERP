package com.tpverp.backend.excel;

import com.tpverp.backend.document.DocumentView;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/excel/product-import")
public class ProductImportController {

    private final ProductImportService service;

    public ProductImportController(ProductImportService service) {
        this.service = service;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','PRODUCTS_WRITE')")
    public ProductImportPreview preview(
            @RequestPart("file") MultipartFile file,
            @RequestPart("mapping") ProductImportMapping mapping)
            throws IOException {
        return service.preview(file.getInputStream(), mapping);
    }

    @PostMapping(value = "/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','PRODUCTS_WRITE')")
    public DocumentView confirm(
            @RequestPart("file") MultipartFile file,
            @RequestPart("request") ProductImportConfirmRequest request,
            Authentication authentication)
            throws IOException {
        return DocumentView.from(service.confirm(file.getInputStream(), request, authentication));
    }
}

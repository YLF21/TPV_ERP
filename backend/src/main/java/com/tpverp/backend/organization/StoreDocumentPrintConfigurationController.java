package com.tpverp.backend.organization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/store-document-print-configuration")
@PreAuthorize("hasRole('ADMIN')")
public class StoreDocumentPrintConfigurationController {

    private final StoreDocumentPrintConfigurationService service;

    public StoreDocumentPrintConfigurationController(
            StoreDocumentPrintConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public StoreDocumentPrintConfigurationService.Configuration get() {
        return service.configuration();
    }

    @PutMapping("/observations")
    public StoreDocumentPrintConfigurationService.Configuration updateObservations(
            @Valid @RequestBody ObservationsRequest request) {
        return service.updateObservations(
                request.ticket(), request.invoice(), request.deliveryNote());
    }

    @PutMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoreDocumentPrintConfigurationService.Configuration uploadLogo(
            @RequestPart("file") MultipartFile file) throws IOException {
        return service.uploadLogo(file.getBytes());
    }

    @DeleteMapping("/logo")
    public StoreDocumentPrintConfigurationService.Configuration removeLogo() {
        return service.removeLogo();
    }

    public record ObservationsRequest(
            @Size(max = 2000) String ticket,
            @Size(max = 2000) String invoice,
            @Size(max = 2000) String deliveryNote) {
    }
}

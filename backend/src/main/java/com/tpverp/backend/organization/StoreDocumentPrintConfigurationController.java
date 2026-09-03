package com.tpverp.backend.organization;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@RequireGestionGroup(GestionGroup.CONFIGURACION)
public class StoreDocumentPrintConfigurationController {

    private final StoreDocumentPrintConfigurationService service;

    public StoreDocumentPrintConfigurationController(
            StoreDocumentPrintConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('DOCUMENT_TEMPLATES_MANAGE'))")
    public StoreDocumentPrintConfigurationService.Configuration get() {
        return service.configuration();
    }

    @PutMapping("/observations")
    @PreAuthorize("hasRole('ADMIN')")
    public StoreDocumentPrintConfigurationService.Configuration updateObservations(
            @Valid @RequestBody ObservationsRequest request) {
        return service.updateObservations(
                request.ticket(), request.invoice(), request.deliveryNote(), request.voucher());
    }

    @PutMapping("/store-name-visibility")
    @PreAuthorize("hasRole('ADMIN')")
    public StoreDocumentPrintConfigurationService.Configuration updateStoreNameVisibility(
            @Valid @RequestBody StoreNameVisibilityRequest request) {
        return service.updateStoreNameVisibility(request.showStoreName());
    }

    @PutMapping("/ticket-style")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('DOCUMENT_TEMPLATES_MANAGE'))")
    public StoreDocumentPrintConfigurationService.Configuration updateTicketStyle(
            @Valid @RequestBody TicketStyleRequest request) {
        return service.updateTicketStyle(request.style());
    }

    @PutMapping("/ticket-presentation")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
            + "hasAuthority('DOCUMENT_TEMPLATES_MANAGE'))")
    public StoreDocumentPrintConfigurationService.Configuration updateTicketPresentation(
            @Valid @RequestBody TicketPresentationRequest request) {
        return service.updateTicketPresentation(request.origin(), request.style());
    }

    @PutMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public StoreDocumentPrintConfigurationService.Configuration uploadLogo(
            @RequestPart("file") MultipartFile file) throws IOException {
        return service.uploadLogo(file.getBytes());
    }

    @DeleteMapping("/logo")
    @PreAuthorize("hasRole('ADMIN')")
    public StoreDocumentPrintConfigurationService.Configuration removeLogo() {
        return service.removeLogo();
    }

    public record ObservationsRequest(
            @Size(max = 2000) String ticket,
            @Size(max = 2000) String invoice,
            @Size(max = 2000) String deliveryNote,
            @Size(max = 2000) String voucher) {
    }

    public record TicketStyleRequest(@NotNull TicketPrintStyle style) {
    }

    public record TicketPresentationRequest(
            @NotNull TicketTemplateOrigin origin,
            @NotNull TicketPrintStyle style) {
    }

    public record StoreNameVisibilityRequest(@NotNull Boolean showStoreName) {
    }
}

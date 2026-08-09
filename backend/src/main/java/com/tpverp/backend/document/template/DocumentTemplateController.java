package com.tpverp.backend.document.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-templates")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
        + "hasAuthority('DOCUMENT_TEMPLATES_MANAGE'))")
public class DocumentTemplateController {

    private final DocumentTemplateCatalogService service;

    public DocumentTemplateController(DocumentTemplateCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public DocumentTemplateCatalogService.CatalogView catalog(
            @RequestParam(defaultValue = "FACTURA_VENTA") DocumentTemplateType type) {
        return service.currentStoreCatalog(type);
    }

    @PostMapping("/store-drafts")
    public DocumentTemplateCatalogService.TemplateView registerStoreDraft(
            @Valid @RequestBody StoreDraftRequest request) {
        return service.registerCurrentStoreDraft(
                request.type(), request.code(), request.name());
    }

    public record StoreDraftRequest(
            @NotNull DocumentTemplateType type,
            @NotBlank
            @Size(min = 3, max = 80)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_]{2,79}") String code,
            @NotBlank @Size(max = 160) String name) {
    }
}

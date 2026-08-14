package com.tpverp.backend.document.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/document-templates")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
        + "hasAuthority('DOCUMENT_TEMPLATES_MANAGE'))")
public class DocumentTemplateController {

    private final DocumentTemplateCatalogService service;
    private final DocumentTemplateArtifactService artifacts;

    public DocumentTemplateController(
            DocumentTemplateCatalogService service,
            DocumentTemplateArtifactService artifacts) {
        this.service = service;
        this.artifacts = artifacts;
    }

    @GetMapping
    public DocumentTemplateCatalogService.CatalogView catalog(
            @RequestParam(defaultValue = "FACTURA_VENTA") DocumentTemplateType type,
            @RequestParam(defaultValue = "A4") DocumentTemplateFormat format) {
        return service.currentStoreCatalog(type, format);
    }

    @PostMapping("/store-drafts")
    public DocumentTemplateCatalogService.TemplateView registerStoreDraft(
            @Valid @RequestBody StoreDraftRequest request) {
        return service.registerCurrentStoreDraft(
                request.type(), request.format(), request.code(), request.name());
    }

    @PostMapping(path = "/{templateId}/artifact",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentTemplateCatalogService.TemplateView uploadArtifact(
            @PathVariable UUID templateId,
            @RequestParam("files") java.util.List<MultipartFile> files) {
        return artifacts.uploadAndValidate(templateId, files);
    }

    @PostMapping("/{templateId}/activate")
    public DocumentTemplateCatalogService.TemplateView activate(
            @PathVariable UUID templateId) {
        return artifacts.activate(templateId);
    }

    @GetMapping("/{templateId}/source")
    public ResponseEntity<byte[]> source(@PathVariable UUID templateId) {
        var source = artifacts.source(templateId);
        var disposition = ContentDisposition.attachment()
                .filename(source.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(source.contentType()))
                .body(source.content());
    }

    public record StoreDraftRequest(
            @NotNull DocumentTemplateType type,
            @NotNull DocumentTemplateFormat format,
            @NotBlank
            @Size(min = 3, max = 80)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_]{2,79}") String code,
            @NotBlank @Size(max = 160) String name) {
    }
}
